package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class SoundConverterLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerSoundConverter");

    public static Map<String, Object> convert() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> sounds = new LinkedHashMap<>();

        int polymerCount = 0;
        int moddedCount = 0;
        
        // 创建一个空的上下文用于获取默认的回退声音
        PacketContext ctx = PacketContext.create();

        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            // 跳过原版声音
            if (id.getNamespace().equals("minecraft")) continue;

            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(id).map(Holder::value).orElse(null);
            if (event == null) continue;

            Map<String, Object> entry = new LinkedHashMap<>();

            // [修复 1] 使用 PolymerSyncedObject 获取同步逻辑对象
            // 这适用于所有 Polymer 声音（无论是通过 PolymerSoundEvent 类还是 registerOverlay Lambda 注册的）
            PolymerSyncedObject<SoundEvent> synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.SOUND_EVENT, event);

            // 1. Polymer 自定义声音处理
            if (synced != null) {
                polymerCount++;
                entry.put("type", "polymer");

                // [修复 2] 使用接口标准方法 getPolymerReplacement 获取客户端看到(听到)的声音
                // 传入 event 本身和上下文
                SoundEvent fallback = synced.getPolymerReplacement(event, ctx);

                // 如果 fallback 为 null 或者就是 event 本身，说明没有特殊的回退设置
                if (fallback != null && fallback != event) {
                    Identifier fallbackId = BuiltInRegistries.SOUND_EVENT.getKey(fallback);
                    entry.put("fallback", fallbackId != null ? fallbackId.toString() : "minecraft:entity.experience_orb.pickup");
                } else {
                    entry.put("fallback", "minecraft:entity.experience_orb.pickup");
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