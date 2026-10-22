package demo;

import java.util.HashMap;
import java.util.Map;

public class Registry {
    private static final Map<String, Integer> NAMES = new HashMap<>();
    private static final Map<Integer, String> CODES = new HashMap<>();

    static {
        NAMES.put("one", 1);
        NAMES.put("two", 2);
    }

    static {
        CODES.put(1, "one");
        CODES.put(2, "two");
    }

    static {
        System.out.println("loaded");
    }

    public static Integer code(String name) {
        return NAMES.get(name);
    }
}
