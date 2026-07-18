package io.github.opspilot.a2a.contract;

import com.google.gson.Gson;

/** Shared JSON codec for the locked HTTP+JSON transport. */
public final class A2aJson {

    private static final Gson GSON = new Gson();

    private A2aJson() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
