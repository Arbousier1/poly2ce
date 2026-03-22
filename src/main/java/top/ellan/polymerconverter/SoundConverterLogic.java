package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class SoundConverterLogic {
    private SoundConverterLogic() {
    }

    public static Map<String, Object> convert() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> sounds = new LinkedHashMap<>();

        PacketContext ctx = PacketContext.create();

        for (Identifier id : BuiltInRegistries.SOUND_EVENT.keySet()) {
            if ("minecraft".equals(id.getNamespace())) {
                continue;
            }

            SoundEvent event = BuiltInRegistries.SOUND_EVENT.getValue(id);
            if (event == null) {
                continue;
            }

            Map<String, Object> cfg = new LinkedHashMap<>();
            PolymerSyncedObject<SoundEvent> synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.SOUND_EVENT, event);

            if (synced != null) {
                cfg.put("type", "polymer");
                SoundEvent fallback = synced.getPolymerReplacement(event, ctx);
                if (fallback != null) {
                    Identifier fallbackId = BuiltInRegistries.SOUND_EVENT.getKey(fallback);
                    cfg.put("fallback", fallbackId == null ? "minecraft:entity.experience_orb.pickup" : fallbackId.toString());
                } else {
                    cfg.put("fallback", "minecraft:entity.experience_orb.pickup");
                }
            } else {
                cfg.put("type", "modded");
            }

            sounds.put(id.toString(), cfg);
        }

        root.put("sounds", sounds);
        return root;
    }
}
