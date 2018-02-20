package ca.jarcode.ascript.func;

@FunctionalInterface
@SuppressWarnings("unused")
public interface ThreeArgVoidFunc<T1, T2, T3> {
    int C_RETURN = 0;
    void call(T1 arg, T2 arg2, T3 arg3);
}
