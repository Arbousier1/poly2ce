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

    /**
     * 转换所有非原版声音事件的配置
     * 用于提取 Polymer 注册的声音及其对应的客户端回退声音
     * @return 声音配置 Map
     */
    public static Map<String, Object> convert() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        Map<String, Object> sounds = new LinkedHashMap<>();

        int polymerCount = 0;
        int moddedCount = 0;
        
        // 创建一个空的通用上下文，用于获取默认的声音替换逻辑
        // 对于大多数静态注册的声音，不需要特定的玩家上下文
        PacketContext ctx = PacketContext.create();

        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            // 1. 跳过 Minecraft 原版声音，因为客户端默认已有
            if (id.getNamespace().equals("minecraft")) continue;

            SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(id).map(Holder::value).orElse(null);
            if (event == null) continue;

            Map<String, Object> entry = new LinkedHashMap<>();

            // 2. 获取 Polymer 同步对象
            // 这能检测该声音是否由 Polymer 托管（包括 PolymerSoundEvent 或通过 Polyemr 覆盖的普通声音）
            PolymerSyncedObject<SoundEvent> synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.SOUND_EVENT, event);

            if (synced != null) {
                // --- Polymer 声音处理逻辑 ---
                polymerCount++;
                entry.put("type", "polymer");

                // 获取客户端实际会听到的声音（Replacement / Fallback）
                SoundEvent fallback = synced.getPolymerReplacement(event, ctx);

                // 如果存在替换且替换不是它自己，则记录该回退声音的 ID
                if (fallback != null && fallback != event) {
                    Identifier fallbackId = BuiltInRegistries.SOUND_EVENT.getKey(fallback);
                    entry.put("fallback", fallbackId != null ? fallbackId.toString() : "minecraft:entity.experience_orb.pickup");
                } else {
                    // 如果是 Polymer 声音但没有定义回退，给一个默认的安全声音，防止客户端无声或报错
                    entry.put("fallback", "minecraft:entity.experience_orb.pickup");
                }
            } else {
                // --- 普通 Mod 声音处理逻辑 ---
                // 这些声音需要客户端安装对应的 Mod 资源包才能听到
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