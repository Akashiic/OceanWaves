package com.akashic.oceanwaves.client;

import java.lang.reflect.Method;

/**
 * Há um shaderpack ligado (Iris do Angelica)? Por reflexão, sem depender do Angelica: a API v0 do Iris
 * ({@code IrisApi.getInstance().isShaderPackInUse()}) ou, na falta, {@code Iris.getCurrentPack()}. Consultado no
 * máximo uma vez por segundo. **[a verificar com o Angelica 2.2.4x e um shaderpack]**
 */
final class ShaderPacks {

    private static long checkedAt;
    private static boolean inUse;
    private static Method apiInstance;
    private static Method apiInUse;
    private static Method currentPack;
    private static boolean resolved;

    private ShaderPacks() {}

    static boolean inUse() {
        long now = System.nanoTime();
        if (now - checkedAt < 1_000_000_000L && checkedAt != 0) {
            return inUse;
        }
        checkedAt = now;
        resolve();
        try {
            if (apiInUse != null) {
                inUse = (Boolean) apiInUse.invoke(apiInstance.invoke(null));
            } else if (currentPack != null) {
                Object pack = currentPack.invoke(null);
                inUse = pack instanceof java.util.Optional ? ((java.util.Optional<?>) pack).isPresent() : pack != null;
            } else {
                inUse = false;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            inUse = false;
        }
        return inUse;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            apiInstance = api.getMethod("getInstance");
            apiInUse = api.getMethod("isShaderPackInUse");
            return;
        } catch (ReflectiveOperationException | LinkageError ignored) {}
        try {
            currentPack = Class.forName("net.coderbot.iris.Iris")
                .getMethod("getCurrentPack");
        } catch (ReflectiveOperationException | LinkageError ignored) {}
    }
}
