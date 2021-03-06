package ca.jarcode.consoles.computer.interpreter;
import ca.jarcode.consoles.Computers;
import ca.jarcode.consoles.Consoles;
import ca.jarcode.consoles.computer.Computer;
import ca.jarcode.consoles.computer.interpreter.func.*;
import net.jodah.typetools.TypeResolver;
import org.bukkit.Bukkit;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is meant to make method lambdas (using :: operator) usable
 * for function mapping. It looks hacky, but it's incredibly useful.
 *
 * It only supports basic/primitive types, I will add lua tables -> java maps later.
 */
public class Lua {

	public static boolean killAll = false;

	public static Map<String, ComputerLibrary> libraries = new ConcurrentHashMap<>();
	public static Map<String, LibFunction> staticFunctions = new ConcurrentHashMap<>();
	public static Map<Thread, FuncPool> pools = new ConcurrentHashMap<>();

	// the function mapping tricks that I am using here is very... controversial for me.
	// this is a split between me wanting to avoid using repetitive code (like below),
	// but also wanting to use references to any method in Java (function pointers!).

	// this helps hugely with making binds for Lua<->Java, but it is definitely the most
	// unique piece of code that I have written.

	public static <R, T1, T2, T3, T4> void map(FourArgFunc<R, T1, T2, T3, T4> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, FourArgFunc.class, true), func));
	}
	public static <R, T1, T2, T3> void map(ThreeArgFunc<R, T1, T2, T3> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, ThreeArgFunc.class, true), func));
	}
	public static <R, T1, T2> void map(TwoArgFunc<R, T1, T2> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, TwoArgFunc.class, true), func));
	}
	public static <R, T1> void map(OneArgFunc<R, T1> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, OneArgFunc.class, true), func));
	}

	public static <T1, T2, T3, T4> void map(FourArgVoidFunc<T1, T2, T3, T4> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, FourArgVoidFunc.class, false), func));
	}
	public static <T1, T2, T3> void map(ThreeArgVoidFunc<T1, T2, T3> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, ThreeArgVoidFunc.class, false), func));
	}
	public static <T1, T2> void map(TwoArgVoidFunc<T1, T2> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, TwoArgVoidFunc.class, false), func));
	}
	public static <T1> void map(OneArgVoidFunc<T1> func, String luaName) {
		staticFunctions.put(luaName, link(resolveArgTypes(func, OneArgVoidFunc.class, false), func));
	}
	public static <R> void map(NoArgFunc<R> func, String luaName) {
		staticFunctions.put(luaName, link(new Class[0], func));
	}
	public static void map(NoArgVoidFunc func, String luaName) {
		staticFunctions.put(luaName, link(new Class[0], func));
	}


	public static <R, T1, T2, T3, T4> void put(FourArgFunc<R, T1, T2, T3, T4> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, FourArgFunc.class, true), func));
	}
	public static <R, T1, T2, T3> void put(ThreeArgFunc<R, T1, T2, T3> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, ThreeArgFunc.class, true), func));
	}
	public static <R, T1, T2> void put(TwoArgFunc<R, T1, T2> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, TwoArgFunc.class, true), func));
	}
	public static <R, T1> void put(OneArgFunc<R, T1> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, OneArgFunc.class, true), func));
	}

	public static <T1, T2, T3, T4> void put(FourArgVoidFunc<T1, T2, T3, T4> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, FourArgVoidFunc.class, false), func));
	}
	public static <T1, T2, T3> void put(ThreeArgVoidFunc<T1, T2, T3> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, ThreeArgVoidFunc.class, false), func));
	}
	public static <T1, T2> void put(TwoArgVoidFunc<T1, T2> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, TwoArgVoidFunc.class, false), func));
	}
	public static <T1> void put(OneArgVoidFunc<T1> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(resolveArgTypes(func, OneArgVoidFunc.class, false), func));
	}
	public static <R> void put(NoArgFunc<R> func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(new Class[0], func));
	}
	public static void put(NoArgVoidFunc func, String luaName, FuncPool pool) {
		pool.functions.put(luaName, link(new Class[0], func));
	}

	public static <R, T1, T2, T3, T4> LibFunction link(FourArgFunc<R, T1, T2, T3, T4> func) {
		return link(resolveArgTypes(func, FourArgFunc.class, true), func);
	}
	public static <R, T1, T2, T3> LibFunction link(ThreeArgFunc<R, T1, T2, T3> func) {
		return link(resolveArgTypes(func, ThreeArgFunc.class, true), func);
	}
	public static <R, T1, T2> LibFunction link(TwoArgFunc<R, T1, T2> func) {
		return link(resolveArgTypes(func, TwoArgFunc.class, true), func);
	}
	public static <R, T1> LibFunction link(OneArgFunc<R, T1> func) {
		return link(resolveArgTypes(func, OneArgFunc.class, true), func);
	}
	public static <T1, T2, T3, T4> LibFunction link(FourArgVoidFunc<T1, T2, T3, T4> func) {
		return link(resolveArgTypes(func, FourArgVoidFunc.class, false), func);
	}
	public static <T1, T2, T3> LibFunction link(ThreeArgVoidFunc<T1, T2, T3> func) {
		return link(resolveArgTypes(func, ThreeArgVoidFunc.class, false), func);
	}
	public static <T1, T2> LibFunction link(TwoArgVoidFunc<T1, T2> func) {
		return link(resolveArgTypes(func, TwoArgVoidFunc.class, false), func);
	}
	public static <T1> LibFunction link(OneArgVoidFunc<T1> func) {
		return link(resolveArgTypes(func, OneArgVoidFunc.class, false), func);
	}
	public static LibFunction link(NoArgVoidFunc func) {
		return link(new Class[0], func);
	}
	public static <R> LibFunction link(NoArgFunc<R> func) {
		return link(new Class[0], func);
	}
	public static void find(Object inst, FuncPool pool) {
		find(inst.getClass(), inst, pool);
	}
	public static void find(Class type, Object inst, FuncPool pool) {
		List<Method> methodList = new ArrayList<>();
		while (type != Object.class) {
			methodList.addAll(Arrays.asList(type.getDeclaredMethods()));
			type = type.getSuperclass();
		}
		methodList.stream()
				.filter(m -> m.getName().startsWith("lua$"))
				.peek(m -> m.setAccessible(true))
				.map(m -> {
					Class[] types = m.getParameterTypes();
					LibFunction function = new LibFunction() {
						@Override
						public LuaValue call() {
							try {
								return translateLua(m.invoke(inst));
							} catch (IllegalAccessException | InvocationTargetException e) {
								throw new LuaError(e);
							}
						}

						@Override
						public LuaValue call(LuaValue v) {
							try {
								return translateLua(m.invoke(inst, toJava(types, v)));
							} catch (IllegalAccessException | InvocationTargetException e) {
								throw new LuaError(e);
							}
						}

						@Override
						public LuaValue call(LuaValue v, LuaValue v1) {
							try {
								return translateLua(m.invoke(inst, toJava(types, v, v1)));
							} catch (IllegalAccessException | InvocationTargetException e) {
								throw new LuaError(e);
							}
						}

						@Override
						public LuaValue call(LuaValue v, LuaValue v1, LuaValue v2) {
							try {
								return translateLua(m.invoke(inst, toJava(types, v, v1, v2)));
							} catch (IllegalAccessException | InvocationTargetException e) {
								throw new LuaError(e);
							}
						}

						@Override
						public LuaValue call(LuaValue v, LuaValue v1, LuaValue v2, LuaValue v3) {
							try {
								return translateLua(m.invoke(inst, toJava(types, v, v1, v2, v3)));
							} catch (IllegalAccessException | InvocationTargetException e) {
								throw new LuaError(e);
							}
						}
					};
					return new AbstractMap.SimpleEntry<>(m.getName().substring(4), function);
				})
				.forEach(entry -> pool.functions.put(entry.getKey(), entry.getValue()));
	}
	public static Object[] toJava(Class[] types, Object... args) {
		for (int t = 0; t < args.length; t++) {
			args[t] = translate(types[t], (LuaValue) args[t]);
		}
		return args;
	}
	// retrieves the computer that the current program is being executed in
	// used in static Java methods that are meant to be visible to lua
	public static Computer context() {
		return findPool().getComputer();
	}

	public static SandboxProgram program() {
		return findPool().getProgram();
	}

	public static boolean terminated() {
		return findPool().getProgram().terminated();
	}

	private static FuncPool findPool() {
		FuncPool pool = pools.get(Thread.currentThread());
		if (pool == null)
			throw new IllegalAccessError("Tried to access lua bindings outside of program thread");
		else return pool;
	}
	public static Class[] resolveArgTypes(Object func, Class<?> type, boolean shift) {
		Class<?>[] arr = TypeResolver.resolveRawArguments(type, func.getClass());
		if (!shift) return arr;
		Class[] ret = new Class[arr.length - 1];
		System.arraycopy(arr, 1, ret, 0, ret.length - 1);
		return ret;
	}
	public static LibFunction link(Class[] types, Object func) {

		if (types.length == 1)
			return new OneArgFunction() {

				@Override
				public LuaValue call(LuaValue v1) {
					return translateLua(Lua.call(func, types, v1));
				}
			};
		else if (types.length == 2)
			return new TwoArgFunction() {

				@Override
				public LuaValue call(LuaValue v1, LuaValue v2) {
					return translateLua(Lua.call(func, types, v1, v2));
				}
			};
		else if (types.length == 3)
			return new ThreeArgFunction() {

				@Override
				public LuaValue call(LuaValue v1, LuaValue v2, LuaValue v3) {
					return translateLua(Lua.call(func, types, v1, v2, v3));
				}
			};
		else if (types.length == 0)
			return new ZeroArgFunction() {
				@Override
				public LuaValue call() {
					return translateLua(Lua.call(func, types));
				}
			};

		return new LibFunction() {
			@Override
			public LuaValue call() {
				return translateLua(Lua.call(func, types));
			}

			@Override
			public LuaValue call(LuaValue v1) {
				return translateLua(Lua.call(func, types, v1));
			}

			@Override
			public LuaValue call(LuaValue v1, LuaValue v2) {
				return translateLua(Lua.call(func, types, v1, v2));
			}

			@Override
			public LuaValue call(LuaValue v1, LuaValue v2, LuaValue v3) {
				return translateLua(Lua.call(func, types, v1, v2, v3));
			}

			@Override
			public LuaValue call(LuaValue v1, LuaValue v2, LuaValue v3, LuaValue v4) {
				return translateLua(Lua.call(func, types, v1, v2, v3, v4));
			}

			@Override
			public LuaValue invoke(Varargs value) {
				Object[] total = new Object[types.length];
				for (int t = 0; t < types.length; t++) {
					total[t] = translate(types[t], value.arg(t));
				}
				return translateLua(Lua.call(func, types, total));
			}
		};
	}
	public static LuaValue translateLua(Object java) {
		if (java == null) {
			return LuaValue.NIL;
		}
		else if (java instanceof LuaValue) {
			return (LuaValue) java;
		}
		else if (java instanceof Boolean) {
			return LuaValue.valueOf((Boolean) java);
		}
		else if (java instanceof Integer) {
			return LuaValue.valueOf((Integer) java);
		}
		else if (java instanceof Byte) {
			return LuaValue.valueOf((Byte) java);
		}
		else if (java instanceof Short) {
			return LuaValue.valueOf((Short) java);
		}
		else if (java instanceof Long) {
			return LuaValue.valueOf((Long) java);
		}
		else if (java instanceof Double) {
			return LuaValue.valueOf((Double) java);
		}
		else if (java instanceof Float) {
			return LuaValue.valueOf((Float) java);
		}
		else if (java instanceof Character) {
			return LuaValue.valueOf(new String(new char[]{(Character) java}));
		}
		else if (java instanceof String) {
			return LuaValue.valueOf((String) java);
		}
		// recursive
		else if (java.getClass().isArray()) {
			Object[] arr = new Object[Array.getLength(java)];
			for (int t = 0; t < arr.length; t++)
				arr[t] = Array.get(java, t);
			return LuaValue.listOf(Arrays.asList(arr).stream()
					.map(Lua::translateLua)
					.toArray(LuaValue[]::new));
		}
		else {
			if (Consoles.debug)
				Computers.getInstance().getLogger().info("[DEBUG] Wrapping java object: " + java.getClass());
			return CoerceJavaToLua.coerce(java);
		}
	}

	public static PartialFunctionBind javaCallable(LuaValue value) {
		if (!value.isfunction()) throw new RuntimeException("expected function");
		return (args) -> {
			LuaValue[] arr = Arrays.asList(args).stream()
					.map(Lua::translateLua)
					.toArray(LuaValue[]::new);
			switch (arr.length) {
				case 0: return value.call();
				case 1: return value.call(arr[0]);
				case 2: return value.call(arr[0], arr[1]);
				case 3: return value.call(arr[0], arr[1], arr[2]);
			}
			throw new RuntimeException("function has too many arguments");
		};
	}

	public static FunctionBind javaFunction(LuaValue value) {
		return (args) -> coerce(javaCallable(value).call(args));
	}

	private static Object coerce(LuaValue value) {
		if (value.isboolean())
			return value.checkboolean();
		else if (value.isnumber())
			return value.checkdouble();
		else if (value.isstring())
			return value.checkjstring();
		else if (value.isfunction())
			return javaFunction(value);
		else if (value.isnil())
			return null;
		else if (value.istable())
			return value;
		else if (value.isuserdata())
			return value.checkuserdata();
		else throw new RuntimeException("could not assume type for: " + value.toString() + " ("
					+ value.getClass().getSimpleName() + ")");
	}
	private static Object translate(Class<?> type, LuaValue value) {
		if (type != null && FunctionBind.class.isAssignableFrom(type)
				|| (value.isfunction() && (TypeResolver.Unknown.class == type || type == null))) {
			return javaFunction(value);
		}
		else if (type != null && LuaValue.class.isAssignableFrom(type)) {
			return value;
		}
		// some of these are unsupported on non Oracle/Open JSE VMs
		else if (type == Runnable.class) {
			if (!value.isfunction()) throw new RuntimeException("expected function");
			return (Runnable) ((LuaFunction) value)::call;
		}
		else if (type == boolean.class || type == Boolean.class
				|| value.isboolean()) {
			return value.checkboolean();
		}
		else if (type == int.class || type == Integer.class
				|| (value.isint() && (TypeResolver.Unknown.class == type || type == null))) {
			return value.checkint();
		}
		else if (type == byte.class || type == Byte.class) {
			return (byte) value.checkint();
		}
		else if (type == short.class || type == Short.class) {
			return (short) value.checkint();
		}
		else if (type == long.class || type == Long.class) {
			return value.checklong();
		}
		else if (type == double.class || type == Double.class) {
			return value.checkdouble();
		}
		else if (type == float.class || type == Float.class) {
			return (float) value.checknumber().checkdouble();
		}
		else if (type == char.class || type == Character.class) {
			return value.checkjstring().charAt(0);
		}
		else if (type == String.class || value.getClass().isAssignableFrom(LuaString.class)) {
			return value.checkjstring();
		}
		else if (value.equals(LuaValue.NIL)) {
			return null;
		}
		else if (value.isuserdata()) {
			return value.checkuserdata();
		}
		else if (type != null && type.isArray() && value.istable()) {
			Class component = type.getComponentType();
			LuaTable table = value.checktable();
			int len = 0;
			for (LuaValue key : table.keys()) {
				if (key.isint() && key.checkint() > len)
					len = key.checkint();
			}
			Object arr = Array.newInstance(component, len);
			for (LuaValue key : table.keys()) {
				if (key.isint())
					Array.set(arr, key.checkint(), translate(component, table.get(key)));
			}
			return arr;
		}
		else throw new RuntimeException("Unsupported argument: " + type
					+ ", lua: " + value.getClass().getSimpleName() + ", data: " + value.toString());
	}
	@SuppressWarnings("unchecked")
	private static Object call(Object func, Class[] types, Object... args) {
		for (int t = 0; t < args.length; t++) {
			args[t] = translate(types[t], (LuaValue) args[t]);
		}
		if (func instanceof OneArgFunc) {
			return ((OneArgFunc) func).call(args[0]);
		}
		else if (func instanceof TwoArgFunc) {
			return ((TwoArgFunc) func).call(args[0], args[1]);
		}
		else if (func instanceof ThreeArgFunc) {
			return ((ThreeArgFunc) func).call(args[0], args[1], args[2]);
		}
		else if (func instanceof FourArgFunc) {
			return ((FourArgFunc) func).call(args[0], args[1], args[2], args[3]);
		}
		else if (func instanceof NoArgFunc) {
			return ((NoArgFunc) func).call();
		}
		else if (func instanceof NoArgVoidFunc) {
			((NoArgVoidFunc) func).call();
			return null;
		}
		else if (func instanceof OneArgVoidFunc) {
			((OneArgVoidFunc) func).call(args[0]);
			return null;
		}
		else if (func instanceof TwoArgVoidFunc) {
			((TwoArgVoidFunc) func).call(args[0], args[1]);
			return null;
		}
		else if (func instanceof ThreeArgVoidFunc) {
			((ThreeArgVoidFunc) func).call(args[0], args[1], args[2]);
			return null;
		}
		else if (func instanceof FourArgVoidFunc) {
			((FourArgVoidFunc) func).call(args[0], args[1], args[2], args[3]);
			return null;
		}
		else throw new RuntimeException("Unsupported interface");
	}
	public static void main(Runnable task) {
		Bukkit.getScheduler().scheduleSyncDelayedTask(Computers.getInstance(), task);
	}
}
