package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
            String fallbackPath = "minecraft:block.note_block.harp";
            if (synced != null) {
                // Touch synced object for consistency, but keep exported path vanilla-safe.
                synced.getPolymerReplacement(event, ctx);
            }
            List<String> entries = new ArrayList<>();
            entries.add(fallbackPath);
            cfg.put("replace", true);
            cfg.put("sounds", entries);

            sounds.put(id.toString(), cfg);
        }

        root.put("sounds", sounds);
        return root;
    }
}
