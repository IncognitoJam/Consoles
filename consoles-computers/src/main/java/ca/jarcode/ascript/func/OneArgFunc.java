package ca.jarcode.ascript.func;

@FunctionalInterface
@SuppressWarnings("unused")
public interface OneArgFunc<R, T> {
    int C_RETURN = 1;
    R call(T arg);
}
