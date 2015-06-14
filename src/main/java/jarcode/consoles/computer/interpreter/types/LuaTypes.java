package jarcode.consoles.computer.interpreter.types;

import jarcode.consoles.Consoles;
import jarcode.consoles.computer.interpreter.Lua;
import jarcode.consoles.computer.manual.ManualManager;

import java.util.ArrayList;
import java.util.List;

public class LuaTypes {

	private static List<String> typeNames = new ArrayList<>();

	static {
		Lua.map(LuaTypes::lua_uniqueTypeNames, "uniqueTypeNames");
		ManualManager.load(LuaTypes.class);
	}

	public static void init() {
		reg(LuaBlock.class);
		reg(LuaBuffer.class);
		reg(LuaChest.class);
		reg(LuaComputer.class);
		reg(LuaFile.class);
		reg(LuaFolder.class);
		reg(LuaFrame.class);
		reg(LuaInteraction.class);
		if (Consoles.componentRenderingEnabled)
			reg(LuaPainter.class);
		reg(LuaTerminal.class);
		reg(LuaTypeBuilder.class);
	}
	private static void reg(Class<?> type) {
		ManualManager.loadType(type);
		typeNames.add(type.getSimpleName());
	}
	private static String[] lua_uniqueTypeNames() {
		return typeNames.toArray(new String[typeNames.size()]);
	}
}
