package com.recorte.export;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection helpers that locate fields by their <em>type</em> instead of their name.
 *
 * <p>Minecraft field names are official (human readable) in the dev workspace but obfuscated
 * (SRG) in the shipped game. Looking fields up by type sidesteps that entirely, so the exporter
 * works in both environments without an access transformer or a mappings table. The classes we
 * read from ({@code ModelPart}, {@code Cube}, {@code Polygon}, {@code Vertex}) each have an
 * unambiguous field of the type we need.
 */
public final class ReflectUtil {
    private ReflectUtil() {}

    private static final ConcurrentHashMap<String, Field> CACHE = new ConcurrentHashMap<>();

    /** First declared field of {@code owner} whose type is exactly {@code type}. */
    public static Field fieldOfType(Class<?> owner, Class<?> type) {
        return CACHE.computeIfAbsent(owner.getName() + "#=" + type.getName(), k -> {
            for (Field f : owner.getDeclaredFields()) {
                if (f.getType() == type) {
                    f.setAccessible(true);
                    return f;
                }
            }
            throw new IllegalStateException("No field of type " + type.getName() + " in " + owner.getName());
        });
    }

    /** First declared array field of {@code owner}. */
    public static Field arrayField(Class<?> owner) {
        return CACHE.computeIfAbsent(owner.getName() + "#[]", k -> {
            for (Field f : owner.getDeclaredFields()) {
                if (f.getType().isArray()) {
                    f.setAccessible(true);
                    return f;
                }
            }
            throw new IllegalStateException("No array field in " + owner.getName());
        });
    }

    /** All declared fields of {@code owner} whose type is exactly {@code type}, in declaration order. */
    public static List<Field> fieldsOfType(Class<?> owner, Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : owner.getDeclaredFields()) {
            if (f.getType() == type) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    public static Object get(Field f, Object owner) {
        try {
            return f.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not read " + f, e);
        }
    }

    public static float getFloat(Field f, Object owner) {
        try {
            return f.getFloat(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not read " + f, e);
        }
    }
}
