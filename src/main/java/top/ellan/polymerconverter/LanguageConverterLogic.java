package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import xyz.nucleoid.packettweaker.PacketContext;

import eu.pb4.polymer.common.impl.FakeWorld;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class LanguageConverterLogic {
    private LanguageConverterLogic() {
    }

    public static Map<String, Object> convert() {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, String> itemEn = new TreeMap<>();
        Map<String, String> blockEn = new TreeMap<>();
        Map<String, String> furnitureEn = new TreeMap<>();

        PacketContext ctx = PacketContext.create();

        for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
            if ("minecraft".equals(id.getNamespace())) {
                continue;
            }
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (item == null) {
                continue;
            }

            boolean isPolymer = item instanceof PolymerItem
                || PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item) instanceof PolymerItem;
            if (!isPolymer) {
                continue;
            }

            ItemStack stack = PolymerItemUtils.getPolymerItemStack(item.getDefaultInstance(), TooltipFlag.NORMAL, ctx);
            String name = stack == null ? item.getName(item.getDefaultInstance()).getString() : stack.getHoverName().getString();
            itemEn.put("item." + id.getNamespace() + "." + id.getPath(), "<!i>" + name);
        }

        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            if ("minecraft".equals(id.getNamespace())) {
                continue;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block == null) {
                continue;
            }

            boolean isPolymer = block instanceof PolymerBlock
                || PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block) instanceof PolymerBlock;
            if (!isPolymer) {
                continue;
            }

            blockEn.put("block_name:" + id.getNamespace() + ":" + id.getPath(), "<!i>" + block.getName().getString());
        }

        for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if ("minecraft".equals(id.getNamespace())) {
                continue;
            }

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            if (type == null) {
                continue;
            }

            Entity sample;
            try {
                sample = type.create(FakeWorld.INSTANCE_UNSAFE, EntitySpawnReason.LOAD);
            } catch (Throwable ignored) {
                sample = null;
            }

            if (sample == null || PolymerEntity.get(sample) == null) {
                continue;
            }

            String key = "furniture." + id.getNamespace() + "." + id.getPath();
            String label = humanize(id.getPath());
            furnitureEn.put(key, label);
        }

        root.put("lang#items", locales(itemEn));
        root.put("lang#blocks", locales(blockEn));
        root.put("lang#furniture", locales(furnitureEn));

        return root;
    }

    private static Map<String, Object> locales(Map<String, String> en) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("en_us", en);
        section.put("zh_cn", new LinkedHashMap<>(en));
        return section;
    }

    private static String humanize(String path) {
        String[] parts = path.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.isEmpty() ? path : builder.toString();
    }
}
