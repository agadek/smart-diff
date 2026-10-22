package util;

import java.lang.reflect.Type;
import java.util.Map;

/** Type checks, after commons-lang TypeUtils. */
public class Types {
    public static boolean isAssignable(Type type, Type toType) {
        return isAssignable(type, toType, null);
    }

    public static boolean isAssignable(Type type, Type toType, Map<String, Type> typeVarAssigns) {
        if (type == null || toType == null) {
            return type == toType;
        }
        return typeVarAssigns == null ? type.equals(toType) : typeVarAssigns.containsValue(toType);
    }

    public static boolean isInstance(Object value, Type type) {
        return type != null && (value == null || isAssignable(value.getClass(), type));
    }
}
