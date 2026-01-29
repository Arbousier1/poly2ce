package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.other.PolymerSoundEvent;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;  // ← 修改这里
import net.minecraft.sounds.SoundEvent;
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

        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {  // ← 修改这里
            // 跳过原版声音
            if (id.getNamespace().equals("minecraft")) continue;

            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(id).map(Holder::value).orElse(null);
            if (event == null) continue;

            Map<String, Object> entry = new LinkedHashMap<>();

            // PolymerSoundEvent 检测
            PolymerSyncedObject<SoundEvent> synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.SOUND_EVENT, event);

            // 1. Polymer 自定义声音处理
            if (synced instanceof PolymerSoundEvent polymerSound) {
                polymerCount++;
                entry.put("type", "polymer");

                try {
                    Field fallbackField = PolymerSoundEvent.class.getDeclaredField("polymerSound");
                    fallbackField.setAccessible(true);
                    SoundEvent fallback = (SoundEvent) fallbackField.get(polymerSound);

                    if (fallback != null) {
                        // 使用 Identifier 类型
                        Identifier fallbackId = BuiltInRegistries.SOUND_EVENT.getKey(fallback);  // ← 修改这里
                        entry.put("fallback", fallbackId != null ? fallbackId.toString() : "unknown");
                    } else {
                        entry.put("fallback", "none");
                    }

                    try {
                        Field sourceField = PolymerSoundEvent.class.getDeclaredField("source");
                        sourceField.setAccessible(true);
                        UUID source = (UUID) sourceField.get(polymerSound);
                        if (source != null) {
                            entry.put("resource_pack_uuid", source.toString());
                        }
                    } catch (NoSuchFieldException ignored) {
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
