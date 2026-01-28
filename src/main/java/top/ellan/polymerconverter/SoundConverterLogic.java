package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.other.PolymerSoundEvent;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject; // 必须导入这个
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class SoundConverterLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerSoundConverter");

    public static Map<String, Object> convert() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> sounds = new LinkedHashMap<>();

        int polymerCount = 0;
        int moddedCount = 0;

        for (Identifier id : Registries.SOUND_EVENT.getIds()) {
            // 跳过原版声音
            if (id.getNamespace().equals("minecraft")) continue;

            SoundEvent event = Registries.SOUND_EVENT.get(id);
            if (event == null) continue;

            Map<String, Object> entry = new LinkedHashMap<>();

            // === 核心修复点 ===
            // PolymerSoundEvent 不再是 SoundEvent 的子类，而是通过 SyncedObject 挂载的
            PolymerSyncedObject<SoundEvent> synced = PolymerSyncedObject.getSyncedObject(Registries.SOUND_EVENT, event);

            // 1. Polymer 自定义声音处理
            if (synced instanceof PolymerSoundEvent polymerSound) {
                polymerCount++;
                entry.put("type", "polymer");

                try {
                    // 提取 Fallback Sound (核心功能)
                    // 这是一个 protected 字段，必须反射
                    Field fallbackField = PolymerSoundEvent.class.getDeclaredField("polymerSound");
                    fallbackField.setAccessible(true);
                    SoundEvent fallback = (SoundEvent) fallbackField.get(polymerSound);

                    if (fallback != null) {
                        // Yarn 映射下使用 getId()
                        entry.put("fallback", Registries.SOUND_EVENT.getKey(fallback).toString());
                    } else {
                        entry.put("fallback", "none");
                    }

                    // 提取 Source UUID (资源包标识)
                    try {
                        Field sourceField = PolymerSoundEvent.class.getDeclaredField("source");
                        sourceField.setAccessible(true);
                        UUID source = (UUID) sourceField.get(polymerSound);
                        if (source != null) {
                            entry.put("resource_pack_uuid", source.toString());
                        }
                    } catch (NoSuchFieldException ignored) {
                        // 旧版本 Polymer 可能没有 source 字段，忽略
                    }

                } catch (NoSuchFieldException e) {
                    entry.put("_error", "field_not_found: polymerSound");
                } catch (IllegalAccessException e) {
                    entry.put("_error", "access_denied");
                } catch (Exception e) {
                    entry.put("_error", "unknown: " + e.getMessage());
                }
            }
            // 2. 普通 Mod 声音
            else {
                moddedCount++;
                entry.put("type", "modded");
            }

            sounds.put(id.toString(), entry);
        }

        LOGGER.info("Sound conversion finished. Polymer: {}, Modded: {}", polymerCount, moddedCount);

        rootConfig.put("sounds", sounds);
        return rootConfig;
    }
}