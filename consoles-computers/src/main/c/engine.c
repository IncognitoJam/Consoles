#include <jni.h>
#include <jni_md.h>

#include <lua.h>
#include <luajit.h> 
#include <lauxlib.h>
#include <lualib.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ffi.h>

#include <LuaEngine.h>

#include "engine.h"

static int engine_debug = 0;
static uint8_t setup = 0;

// we use a single caller interface for wrapping Java -> C -> Lua functions,
// since they all boil down to 'void (*lua_cfunc) (lua_State* state)'
static ffi_cif func_cif;
// again, this is the single caller interface used for hook functions
static ffi_cif hook_cif;

static int maxtime = 7000;

/*
 * There are some resources that accumulate over the life of the Lua VM. The reason
 * why we cannot collect these during the VM's lifetime is because it's impossible
 * to determine when they are no longer needed, and because it's not plausible
 * to rely on Lua code (as it is untrusted) to close these resources.
 * 
 * So, we have to close them at the end of the lifecycle.
 */
void engine_close(JNIEnv* env, engine_inst* inst) {
	
	// close lua entirely
	lua_close(inst->state);
	
	// if the engine was already marked closed, do nothing
	if (inst->closed) return;
	
	// Free all function wrappers (and underlying ffi closures).
	// the amount of closures registered will continue to grow as
	// java functions are registered, and will only be free'd when
	// the entire engine is closed.
	int t;
	for (t = 0; t < inst->wrappers_amt; t++) {
		// free closure
		ffi_closure_free(inst->wrappers[t]->closure);
		
		// delete global reference to java function
		if (inst->wrappers[t]->obj_inst) { // if it's null, it was for a reflected static function
			(*env)->DeleteGlobalRef(env, inst->wrappers[t]->obj_inst);
		}
		
		// reflected methods have a Method instance to be deleted
		if (inst->wrappers[t]->type == ENGINE_JAVA_REFLECT_FUNCTION) {
			(*env)->DeleteGlobalRef(env, inst->wrappers[t]->data.reflect.method);
		}
		
		free(inst->wrappers[t]);
	}
	
	// Free all floating java global references. These are mainly
	// used by lua userdatums that 'float' around in the lua VM
	// and have an undefined lifecycle
	for (t = 0; t < inst->floating_objects_amt; t++) {
		(*env)->DeleteGlobalRef(env, inst->floating_objects[t]);
	}
	
	// this purges the value mappings for any values (attached to this instance) that
	// have not already been released
	engine_clearvalues(env, inst);
	
	// free wrapper stack
	free(inst->wrappers);
	inst->wrappers = 0;
	inst->wrappers_amt = 0;
	
	// free floating object stack
	free(inst->floating_objects);
	inst->floating_objects = 0;
	inst->floating_objects_amt = 0;
	
	// free closure used for hook function
	ffi_closure_free(inst->closure);
	
	// free instance struct
	free(inst);
}

// unused, probably should be used
void engine_removewrapper(engine_inst* inst, engine_jfuncwrapper* wrapper) {
	if (inst->wrappers_amt == 0) return;
	if (inst->wrappers_amt == 1) {
		free(inst->wrappers);
	}
	else {
		uint8_t valid = 0;
		int t;
		for (t = 0; t < inst->wrappers_amt; t++) {
			if (wrapper == inst->wrappers[t]) {
				valid = 1;
				break;
			}
		}
		if (!valid) return;
		if (t != inst->wrappers_amt) {
			engine_jfuncwrapper** ptr = &(inst->wrappers[t]); // pointer to element t
			memmove(ptr, ptr + 1, inst->wrappers_amt - (t + 1));
		}
		inst->wrappers = realloc(inst->wrappers, (inst->wrappers_amt - 1) * sizeof(void*));
	}
	inst->wrappers_amt--;
}

void engine_regwrapper(engine_inst* inst, engine_jfuncwrapper* wrapper) {
	if (inst->wrappers_amt == 0) {
		inst->wrappers = malloc(sizeof(engine_jfuncwrapper*));
	}
	else {
		inst->wrappers = realloc(inst->wrappers, (inst->wrappers_amt + 1) * sizeof(engine_jfuncwrapper*));
	}
	inst->wrappers[inst->wrappers_amt] = wrapper;
	inst->wrappers_amt++;
}

static void hook(engine_inst* inst, lua_State* state, lua_Debug* debug) {
	
	// when we've figured out that the script has been killed, we
	// need to constantly error out of each function until we're
	// out of the script. Gernerally we only need to do this once,
	// but lua programs have the possibility to trap their own errors,
	// so this ensures that no lua code can continue to run
	//
	// there is an SO answer on this problem, and somehow the
	// accepted solution is to use setjmp/longjmp. That is a terrible
	// solution, because we're dealing with our own ffi functions
	// (unsafe to jmp out of), and luajit (jit compiled, lots of
	// undefined stuff could happen), and other internal ffi usages.
	
	if (inst->killed) {
		// re-set hook to constantly execute
		lua_sethook(state, inst->hook, LUA_MASKLINE, 0);
		// error
		luaL_error(state, "C: killed, exiting frame");
	}
}

// binding hook for ffi
static void handle_hook(ffi_cif* cif, void* ret, void* args[], void* user_data) {
	hook((engine_inst*) user_data, *(lua_State**) args[0], *(lua_Debug**) args[1]);
}

static inline void abort_ffi() {
	fprintf(stderr, "\nfailed to prepare ffi caller interface for C function wrappers\n");
	exit(-1);
}

static inline void abort_ffi_alloc() {
	fprintf(stderr, "\nfailed to allocate ffi closure\n");
	exit(-1);
}

static inline void abort_ffi_prep() {
	fprintf(stderr, "\nfailed to prepare ffi closure\n");
	exit(-1);
}

static void setup_closures() {
	if (!FFI_CLOSURES) {
		fprintf(stderr, "\nFFI_CLOSURES are not supported on this architecture (libffi)\n");
		exit(-1);
	}
	
	// ffi function args
	ffi_type* f_args[1];
	f_args[0] = &ffi_type_pointer;
	// prepare caller interface
	if (ffi_prep_cif(&func_cif, FFI_DEFAULT_ABI, 1, &ffi_type_sint, f_args) != FFI_OK) {
		abort_ffi();
	}
	ffi_type* h_args[2];
	f_args[0] = &ffi_type_pointer;
	f_args[1] = &ffi_type_pointer;
	if (ffi_prep_cif(&hook_cif, FFI_DEFAULT_ABI, 2, &ffi_type_void, h_args) != FFI_OK) {
		abort_ffi();
	}
}

static void setup_classes(JNIEnv* env) {
	// class registering
	classreg(env, "java/lang/Object", &class_object);
	classreg(env, ENGINE_CLASS, &class_type);
	classreg(env, ENGINE_LUA_CLASS, &class_lua);
	classreg(env, ENGINE_ERR_CLASS, &exclass);
	classreg(env, "java/lang/reflect/Method", &class_method);
	
	// Object ids
	id_hashcode = (*env)->GetMethodID(env, class_object, "hashCode", "()I");
	
	// Method ids
	id_methodcall = (*env)->GetMethodID(env, class_method, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
	id_methodcount = (*env)->GetMethodID(env, class_method, "getParameterCount", "()I");
	id_methodtypes = (*env)->GetMethodID(env, class_method, "getParameterTypes", "()[Ljava/lang/Class;");
	
	// Class ids
	id_comptype = (*env)->GetMethodID(env, class_type, "getComponentType", "()Ljava/lang/Class;");
	
	// Lua ids
	id_methodresolve = (*env)->GetStaticMethodID(env, class_lua, "resolveMethod", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;");
	id_methodid = (*env)->GetStaticMethodID(env, class_lua, "methodId", "(Ljava/lang/reflect/Method;)J");
	
	char buf[128] = {0};
	strcat(buf, "(Ljava/lang/Object;)L");
	strcat(buf, ENGINE_VALUE_INTERFACE);
	strcat(buf, ";");
	id_translatevalue = (*env)->GetStaticMethodID(env, class_lua, "translateToScriptValue", buf);
	
	memset(buf, 0, sizeof(buf));
	strcat(buf, "(Ljava/lang/Class;L");
	strcat(buf, ENGINE_VALUE_INTERFACE);
	strcat(buf, ";)Ljava/lang/Object;");
	id_translate = (*env)->GetStaticMethodID(env, class_lua, "translate", buf);
}

// This function allows users to release the global reference
// from an object early. This isn't required, but it's nice.
static int engine_releaseobj(lua_State* state) {
	// first arg should be userdata
	engine_userdata* d = (engine_userdata*) luaL_checkudata(state, 1, "Engine.userdata");
	if (d && !(d->released)) {
		JNIEnv* env  = d->engine->runtime_env;
		(*env)->DeleteGlobalRef(env, d->obj);
		d->released = 1;
	}
	return 0;
}

// this is a function that handles calls on userdata
// this can be optimized, but to improve speed, all types passed to this layer
// would have to be mapped out (and cleaned up), instead of doing it on the fly.
int engine_handleobjcall(lua_State* state) {
	// first arg should be userdata
	engine_userdata* d = luaL_checkudata(state, 1, "Engine.userdata");
	luaL_argcheck(state, d != 0, 1, "`object' expected");
	// second arg should be string, indexing a java object with anything else makes no sense
	const char* str = lua_tostring(state, 2);
	
	jobject obj = d->obj;
	JNIEnv* env = d->engine->runtime_env;
	if (!env || !obj || !d || d->released) {
		lua_pushnil(state);
		return 1;
	}
	if (strcmp(str, "release")) {
		lua_pushcfunction(state, &engine_releaseobj);
		return 1;
	}
	jstring jstr = (*env)->NewStringUTF(env, str);
	jobject method = (*env)->CallStaticObjectMethod(env, class_lua, id_methodresolve, obj, jstr);
	if (method) {
		engine_pushreflect(env, d->engine, method, obj);
	}
	else {
		lua_pushnil(state);
	}
	return 1;
}

JNIEXPORT jlong JNICALL Java_jni_LuaEngine_setupinst(JNIEnv* env, jobject this, jint mode, jlong heap, jint interval) {
	
	if (!setup) {
		function_index = 0;
		
		setup_closures();
		setup_classes(env);
		setup_value(env);
		
		setup = 1;
	}
	
	engine_inst* instance = malloc(sizeof(engine_inst));
	
	void* hook_binding = 0;
	instance->closure = ffi_closure_alloc(sizeof(ffi_closure), &hook_binding); // allocate hook closure
	if (!(instance->closure)) abort_ffi_alloc();
	
	if (ffi_prep_closure_loc(instance->closure, &hook_cif, &handle_hook, instance, hook_binding) != FFI_OK) {
		abort_ffi_prep();
	}
	
	lua_State* state = lua_open();
	
	// set LuaJIT mode
	switch (mode) {
		case 1:
			luaJIT_setmode(state, 0, LUAJIT_MODE_ENGINE | LUAJIT_MODE_ON);
			break;
		case 0:
			luaJIT_setmode(state, 0, LUAJIT_MODE_ENGINE | LUAJIT_MODE_OFF);
			break;
	}
	
	luaopen_base(state);
	luaopen_table(state);
	luaopen_math(state);
	luaopen_string(state);
	luaopen_table(state);
	luaopen_debug(state);
	
	// I/O is handled by overloading print/write in Java code. Unlike LuaJ (which does its own
	// internal magic and needs a stream to print to), LuaJIT just calls print() and write(),
	// which are already likely c functions.
	
	// assign hook function ptr for later use in kill handling
	instance->hook = (lua_Hook) hook_binding;
	
	// set hook to run over an interval
	lua_sethook(state, (lua_Hook) hook_binding, LUA_MASKCOUNT, interval);
	
	// register generic userdata table (for java objects)
	luaL_newmetatable(state, "Engine.userdata");
	// we are setting the special __index key, which lua calls every time it tries to index an object
	lua_pushstring(state, "__index");
	// attach our function to handle generic calls into an object
	lua_pushcfunction(state, &engine_handleobjcall);
	// set key and value
	lua_settable(state, -3);
	// pop metatable off the stack
	lua_pop(state, 1);
	
	// set the implementation type
	lua_pushstring(state, ENGINE_TYPE);
	lua_setglobal(state, ENGINE_TYPE_KEY);
	
	// create and set function registry
	lua_newtable(state);
	lua_setglobal(state, FUNCTION_REGISTRY);
	
	instance->state = state;
	instance->closed = 0;
	instance->restricted = 1;
	
	return (jlong) instance;
}

// this can be called from any thread!
// we shouldn't have to worry about anything here though, just a few dereferencing and
// setting a volatile flag.
JNIEXPORT void JNICALL Java_jni_LuaEngine_kill(JNIEnv* env, jobject this, jlong ptr) {
	engine_inst* inst = (engine_inst*) ptr;
	inst->killed = 1;
}

JNIEXPORT jobject JNICALL Java_jni_LuaEngine_load(JNIEnv* env, jobject this, jlong ptr, jstring jraw) {
	engine_inst* inst = (engine_inst*) ptr;
	lua_State* state = inst->state;
	const char* characters = (*env)->GetStringUTFChars(env, jraw, 0);
	size_t len = strlen(characters);
	// I am using malloc instead of doing this on the stack,
	// because this string could potentially be very large
	char* raw = malloc(sizeof(char) * len);
	memmove(raw, characters, len);
	(*env)->ReleaseStringUTFChars(env, jraw, characters);
	luaL_loadstring(state, raw);
	free(raw);
	engine_value* value = engine_newvalue(env, inst);
	engine_handleregistry(env, inst, state, value);
	return engine_wrap(env, value);
}

JNIEXPORT jlong JNICALL Java_jni_LuaEngine_unrestrict(JNIEnv* env, jobject this, jlong ptr) {
	engine_inst* inst = (engine_inst*) ptr;
	if (inst->restricted) {
		luaopen_package(inst->state);
		luaopen_io(inst->state);
		luaopen_ffi(inst->state);
		luaopen_jit(inst->state);
		luaopen_os(inst->state);
		luaopen_bit(inst->state);
		inst->restricted = 0;
	}
	return ptr;
}

JNIEXPORT void JNICALL Java_jni_LuaEngine_settable
(JNIEnv* env, jobject this, jlong ptr, jstring jtable, jstring jkey, jobject jvalue) {
	engine_inst* inst = (engine_inst*) ptr;
	lua_State* state = inst->state;
	const char* table = (*env)->GetStringUTFChars(env, jtable, 0);
	const char* key = (*env)->GetStringUTFChars(env, jkey, 0);
	
	// get and push table
	lua_getglobal(state, table);
	// is nil
	if (lua_isnil(state, -1)) {
		// pop nil
		lua_pop(state, 1);
		// push new table
		lua_newtable(state);
	}
	// push key
	lua_pushstring(state, key);
	// push value
	engine_value* value = engine_unwrap(env, jvalue);
	if (value) {
		engine_pushvalue(env, inst, state, value);
	}
	else {
		lua_pushnil(state);
	}
	// set table, pops key & value
	lua_settable(state, -3);
	// pop table
	lua_pop(state, 1);
	
	(*env)->ReleaseStringUTFChars(env, jtable, table);
	(*env)->ReleaseStringUTFChars(env, jkey, key);
}

JNIEXPORT jint JNICALL Java_jni_LuaEngine_destroyinst(JNIEnv* env, jobject this, jlong ptr) {
	engine_close(env, (engine_inst*) ptr);
	return 0;
}

JNIEXPORT void JNICALL Java_jni_LuaEngine_setdebug(JNIEnv* env, jobject this, jint mode) {
	engine_debug = mode;
}

JNIEXPORT void JNICALL Java_jni_LuaEngine_interruptreset(JNIEnv* env, jobject this, jlong ptr) {
	// stub
}

JNIEXPORT void JNICALL Java_jni_LuaEngine_setmaxtime(JNIEnv* env, jobject this, jint maxtime) {
	// stub
}

JNIEXPORT jobject JNICALL Java_jni_LuaEngine_wrapglobals(JNIEnv* env, jobject this, jlong ptr) {
	engine_inst* inst = (engine_inst*) ptr;
	engine_value* v = engine_newvalue(env, inst);
	v->type = ENGINE_LUA_GLOBALS;
	return engine_wrap(env, v);
}

// this is a (wrapped) function that handles _all_ C->Lua function calls
int engine_handlecall(engine_jfuncwrapper* wrapper, lua_State* state) {
	JNIEnv* env = wrapper->engine->runtime_env;
	engine_inst* inst = wrapper->engine;
	
	uint8_t vargs = 0;
	switch (wrapper->type) {
		case ENGINE_JAVA_LAMBDA_FUNCTION:
			vargs = wrapper->data.lambda.args;
			break;
		case ENGINE_JAVA_REFLECT_FUNCTION:
			vargs = (*env)->CallIntMethod(env, wrapper->data.reflect.method, id_methodcount);
			break;
	}
	
	// something should be done to truncate extra arguments, because we're
	// operating on the top of the stack
	lua_settop(state, vargs); // truncate
	
	engine_value* v_args[vargs];
	
	// backwards iterate so we get our arguments in order
	int t;
	for (t = vargs - 1; t >= 0; t--) {
		v_args[t] = engine_popvalue(env, inst, state);
		// if null pointer, create new nill value
		if (!v_args[t]) {
			v_args[t] = engine_newvalue(env, inst);
		}
	}
	
	// return value (in java)
	jobject ret = 0;
	
	// You cannot magically pass varadic amounts between functions in C,
	// so this is a bit ugly.
	//
	// On the bright side, this is actually really fast. The method ids
	// were dynamically resolved on creation of the closure, so every
	// time a 'lambda' function is called, it only ends up being a
	// single JNI call.
	//
	// I also could do this with Lua.callAndRelease(...), but I avoid
	// so much more overhead doing it this way.
	if (wrapper->type == ENGINE_JAVA_LAMBDA_FUNCTION) {
		if (wrapper->data.lambda.ret) {
			switch (vargs) {
				case 0:
					ret = (*env)->CallObjectMethod(env, wrapper->obj_inst, wrapper->data.lambda.id);
					break;
				case 1:
					ret = (*env)->CallObjectMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]));
					break;
				case 2:
					ret = (*env)->CallObjectMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]), engine_wrap(env, v_args[1]));
					break;
				case 3:
					ret = (*env)->CallObjectMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]), engine_wrap(env, v_args[1]), engine_wrap(env, v_args[2]));
					break;
				case 4:
					ret = (*env)->CallObjectMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]), engine_wrap(env, v_args[1]), engine_wrap(env, v_args[2]),
						engine_wrap(env, v_args[3]));
					break;
			}
		}
		else {
			switch (vargs) {
				case 0:
					(*env)->CallVoidMethod(env, wrapper->obj_inst, wrapper->data.lambda.id);
					break;
				case 1:
					(*env)->CallVoidMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]));
					break;
				case 2:
					(*env)->CallVoidMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]), engine_wrap(env, v_args[1]));
					break;
				case 3:
					(*env)->CallVoidMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]), engine_wrap(env, v_args[1]), engine_wrap(env, v_args[2]));
					break;
				case 4:
					(*env)->CallVoidMethod(env, wrapper->obj_inst, wrapper->data.lambda.id,
						engine_wrap(env, v_args[0]), engine_wrap(env, v_args[1]), engine_wrap(env, v_args[2]),
						engine_wrap(env, v_args[3]));
					break;
			}
		}
	}
	
	// this has a lot of overhead, but we really don't have any other choice for reflected
	// functions.
	else if (wrapper->type == ENGINE_JAVA_REFLECT_FUNCTION) {
		// Class[]
		jobject paramtypes = (*env)->CallObjectMethod(env, wrapper->data.reflect.method, id_methodtypes);
		// Object[]
		jobjectArray arr = (*env)->NewObjectArray(env, vargs, class_object, 0);
		for (t = 0; t < vargs; t++) {
			// get element type (Class)
			jobject element_type = (*env)->GetObjectArrayElement(env, paramtypes, t);
			// get corresponding ScriptValue
			jobject element = engine_wrap(env, v_args[t]);
			// translate to Object
			jobject translated = (*env)->CallStaticObjectMethod(env, class_lua, id_translate, element_type, element);
			// set index at Object[] array to element
			(*env)->SetObjectArrayElement(env, arr, t, translated);
		}
		// call Method
		ret = (*env)->CallObjectMethod(env, wrapper->data.reflect.method, id_methodcall, wrapper->obj_inst, arr);
	}
	
	// all the argument (engine) values were created just now,
	// and won't be used for anything else.
	//
	// We could further improve this by allocating the engine
	// values on the stack (and reworking some other functions),
	// but that is for another day.
	for (t = 0; t < vargs; t++) {
		engine_releasevalue(env, v_args[t]);
	}
			
	// directly returned null, or no return value, just push nil
	if (!ret) {
		lua_pushnil(state);
		return 1;
	}
	
	// call back into java to map the java value to our factory, and then spit out
	// the ScriptValue [ Lua.translateToScriptValue(Object) ]
	jobject wrapped = (*env)->CallStaticObjectMethod(env, class_lua, id_translatevalue, ret);
	
	// unwrap and push
	
	// translate returned null, push nil
	if (!wrapped) {
		lua_pushnil(state);
	}
	else {
		engine_value* v = engine_unwrap(env, wrapped);
		// if there is no mapped value, something went wrong (premature release?), just push nil
		if (!v) {
			lua_pushnil(state);
		}
		else {
			engine_pushvalue(env, inst, state, v);
			// release value, we won't be seeing this again
			engine_releasevalue(env, v);
		}
	}
	
	return 1;
}

// binding function for ffi
void engine_handlecall_binding(ffi_cif* cif, void* ret, void* args[], void* user_data) {
	*(ffi_arg*) ret = engine_handlecall((engine_jfuncwrapper*) user_data, *(lua_State**) args[0]);
}

engine_lambda_info engine_getlambdainfo(JNIEnv* env, engine_inst* inst, jclass jfunctype) {
	jfieldID fid_return = (*env)->GetStaticFieldID(env, jfunctype, "C_RETURN", "I");
	jfieldID fid_args = (*env)->GetStaticFieldID(env, jfunctype, "C_ARGS", "I");
	jint ret = (*env)->GetStaticIntField(env, jfunctype, fid_return);
	jint args = (*env)->GetStaticIntField(env, jfunctype, fid_args); 
	engine_lambda_info info = {.ret = ret, .args = args};
	return info;
}

// magic to turn Java lambda function wrapper (NoArgFunc, TwoArgVoidFunc, etc) into a C function
// and then pushes it onto the lua stack.
void engine_pushlambda(JNIEnv* env, engine_inst* inst, jobject jfunc, jobject class_array) {
	if (engine_debug) {
		printf("\nwrapping java lambda function from C\n");
	}
	
	// get class
	jclass jfunctype = (*env)->GetObjectClass(env, jfunc);
	
	// obtain func (lambda) info
	uint8_t ret, args;
	{
		engine_lambda_info info = engine_getlambdainfo(env, inst, jfunctype);
		ret = info.ret;
		args = info.args;
	}
	
	// obtain argument info
	
	// you might ask "why not just get the method signature?", well that's because reflecting the class
	// and then getting the signature would probably be harder (and slower).
	
	// build signature and get method
	char buf[128] = {0};
	strcat(buf, "(");
	size_t i;
	for (i = 0; i < args; i++)
		strcat(buf, "Ljava/lang/Object;");
	if (ret) strcat(buf, ")Ljava/lang/Object;");
	else strcat(buf, ")V");
	
	jmethodID mid = (*env)->GetMethodID(env, jfunctype, "call", buf);
	void *func_binding = 0; // our function pointer
	ffi_closure* closure = ffi_closure_alloc(sizeof(ffi_closure), &func_binding); // ffi closure
	
	// this shouldn't happen
	if (!closure) {
		abort_ffi_alloc();
	}
	
	engine_jfuncwrapper* wrapper = malloc(sizeof(engine_jfuncwrapper));
	engine_regwrapper(inst, wrapper);
	
	if (ffi_prep_closure_loc(closure, &func_cif, &engine_handlecall_binding, wrapper, func_binding) != FFI_OK) {
		abort_ffi_prep();
	}
	
	wrapper->closure = closure;
	wrapper->type == ENGINE_JAVA_LAMBDA_FUNCTION;
	wrapper->data.lambda.ret = (uint8_t) ret;
	wrapper->data.lambda.args = (uint8_t) args;
	wrapper->data.lambda.id = mid;
	wrapper->obj_inst = (*env)->NewGlobalRef(env, jfunc);
	wrapper->engine = inst;
	
	lua_pushcfunction(inst->state, (lua_CFunction) func_binding);
}

// same idea as above, but with reflection types instead (Method). We also do a lookup in
// this implementation to find methods that have already been wrapped to consverve memory over
// the lifetime of a lua VM/interpreter.
void engine_pushreflect(JNIEnv* env, engine_inst* inst, jobject reflect_method, jobject obj_inst) {
	if (engine_debug) {
		printf("\nwrapping java reflect function from C\n");
	}
	
	// compute method id
	long id = (*env)->CallStaticLongMethod(env, class_lua, id_methodid, reflect_method);
	// search for reflect wrapper with equal id
	size_t t;
	for(t = 0; t < inst->wrappers_amt; t++) {
		engine_jfuncwrapper* wrapper = inst->wrappers[t];
		if (wrapper->type == ENGINE_JAVA_REFLECT_FUNCTION && wrapper->data.reflect.reflect_id == id) {
			// found identical wrapper, recycle it and return;
			lua_pushcfunction(inst->state, wrapper->func);
			return;
		}
	}
	
	void *func_binding; // our function pointer
	ffi_closure* closure = ffi_closure_alloc(sizeof(ffi_closure), &func_binding); // ffi closure
	
	// this shouldn't happen
	if (!closure) {
		abort_ffi_alloc();
	}
	
	engine_jfuncwrapper* wrapper = malloc(sizeof(engine_jfuncwrapper));
	engine_regwrapper(inst, wrapper);
	
	if (ffi_prep_closure_loc(closure, &func_cif, &engine_handlecall_binding, wrapper, func_binding) != FFI_OK) {
		abort_ffi_prep();
	}
	
	wrapper->closure = closure;
	wrapper->type = ENGINE_JAVA_REFLECT_FUNCTION;
	wrapper->data.reflect.method = (*env)->NewGlobalRef(env, reflect_method);
	wrapper->data.reflect.reflect_id = id;
	wrapper->obj_inst = (*env)->NewGlobalRef(env, obj_inst);
	wrapper->engine = inst;
	wrapper->func = (lua_CFunction) func_binding;
	
	lua_pushcfunction(inst->state, (lua_CFunction) func_binding);
}

void engine_addfloating(engine_inst* inst, jobject reference) {
	if (inst->floating_objects_amt == 0) {
		inst->floating_objects = malloc(sizeof(jobject*));
	}
	else {
		inst->floating_objects = realloc(inst->floating_objects, (inst->floating_objects_amt + 1) * sizeof(jobject*));
	}
	inst->floating_objects[inst->floating_objects_amt] = reference;
	inst->wrappers_amt++;
}

void engine_removefloating(engine_inst* inst, jobject reference) {
	if (inst->floating_objects_amt == 0) return;
	if (inst->floating_objects_amt == 1) {
		free(inst->floating_objects);
	}
	else {
		uint8_t valid = 0;
		size_t t;
		for (t = 0; t < inst->floating_objects_amt; t++) {
			if (reference == inst->floating_objects[t]) {
				valid = 1;
				break;
			}
		}
		if (!valid) return;
		if (t != inst->floating_objects_amt) {
			jobject* ptr = &(inst->floating_objects[t]); // pointer to element t
			memmove(ptr, ptr + 1, inst->floating_objects_amt - (t + 1));
		}
		inst->floating_objects = realloc(inst->floating_objects, (inst->floating_objects_amt - 1) * sizeof(void*));
	}
	inst->floating_objects_amt--;
}

// this is how we handle function calls from Java, but it needs to be improved to properly
// pass errors to Java as exceptions.
//
// errors are currently just printed to stderr (cannot be changed), so this is a stub. The
// proper way to do this would to wrap it in a Java exception and let it do what it wants
// with the strings.
//
// should be called with function in stack, followed by arguments
engine_value* engine_call(JNIEnv* env, engine_inst* inst, lua_State* state, int nargs) {
	inst->runtime_env = env;
	lua_getglobal(state, "debug");
	lua_getfield(state, -1, "traceback");
	lua_remove(state, -2);
	int idx = -nargs - 2;
	lua_insert(state, idx);
	int err = 0;
	if (!(inst->killed))
		err = lua_pcall(state, nargs, 1, idx);
	switch (err) {
		case LUA_ERRRUN: // runtime error
			throw(env, "C: runtime error");
			break;
		case LUA_ERRMEM: // memory alloc error (no lua error is thrown)
			throw(env, "C: memory allocation error");
			break;
		case LUA_ERRERR: // error in error handler
			throw(env, "C: error in error handler");
			break;
	}
	engine_value* ret = engine_popvalue(env, inst, state);
	lua_pop(state, 1);
	return ret;
}
