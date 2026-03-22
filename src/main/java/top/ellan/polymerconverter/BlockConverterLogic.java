package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class BlockConverterLogic {
    private BlockConverterLogic() {
    }

    public static Map<String, Object> convert(Block registeredBlock, PolymerBlock polymerBlock, ServerLevel level) {
        Map<String, Object> blockConfig = new LinkedHashMap<>();

        BlockState defaultState = registeredBlock.defaultBlockState();
        PacketContext ctx = PacketContext.create(level.registryAccess());

        BlockState visualState;
        try {
            visualState = PolymerBlockUtils.getBlockStateSafely(polymerBlock, defaultState, ctx);
        } catch (Throwable ignored) {
            visualState = defaultState;
        }
        Identifier visualBlockId = BuiltInRegistries.BLOCK.getKey(visualState.getBlock());
        if (visualBlockId == null || !"minecraft".equals(visualBlockId.getNamespace())) {
            return Map.of();
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("hardness", defaultState.getDestroySpeed(level, BlockPos.ZERO));
        settings.put("resistance", registeredBlock.getExplosionResistance());

        Map<String, String> sounds = new LinkedHashMap<>();
        String blockPath = BuiltInRegistries.BLOCK.getKey(visualState.getBlock()).getPath();
        sounds.put("break", "minecraft:block." + blockPath + ".break");
        sounds.put("place", "minecraft:block." + blockPath + ".place");
        sounds.put("hit", "minecraft:block." + blockPath + ".hit");
        sounds.put("step", "minecraft:block." + blockPath + ".step");
        sounds.put("fall", "minecraft:block." + blockPath + ".fall");
        settings.put("sounds", sounds);

        List<String> tags = inferTags(defaultState);
        if (!tags.isEmpty()) {
            settings.put("tags", tags);
        }

        blockConfig.put("settings", settings);

        StateDefinition<Block, BlockState> stateDef = registeredBlock.getStateDefinition();
        Collection<Property<?>> properties = stateDef.getProperties();

        if (properties.isEmpty()) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("state", formatBlockState(visualState));
            blockConfig.put("state", state);
        } else {
            Map<String, Object> states = new LinkedHashMap<>();

            Map<String, Object> propDefs = new LinkedHashMap<>();
            for (Property<?> property : properties) {
                propDefs.put(property.getName(), toPropertyDefinition(property, defaultState));
            }
            states.put("properties", propDefs);

            Map<String, Object> appearances = new LinkedHashMap<>();
            Map<String, Object> variants = new LinkedHashMap<>();

            for (BlockState candidateState : stateDef.getPossibleStates()) {
                String variantKey = variantKey(candidateState, properties);
                BlockState candidateVisual;
                try {
                    candidateVisual = PolymerBlockUtils.getBlockStateSafely(polymerBlock, candidateState, ctx);
                } catch (Throwable ignored) {
                    candidateVisual = candidateState;
                }
                Identifier candidateVisualId = BuiltInRegistries.BLOCK.getKey(candidateVisual.getBlock());
                if (candidateVisualId == null || !"minecraft".equals(candidateVisualId.getNamespace())) {
                    continue;
                }

                Map<String, Object> appearance = new LinkedHashMap<>();
                appearance.put("state", formatBlockState(candidateVisual));
                appearances.put(variantKey, appearance);

                Map<String, Object> variant = new LinkedHashMap<>();
                variant.put("appearance", variantKey);
                variants.put(variantKey, variant);
            }

            if (appearances.isEmpty() || variants.isEmpty()) {
                return Map.of();
            }
            states.put("appearances", appearances);
            states.put("variants", variants);
            blockConfig.put("states", states);
        }

        Map<String, Object> loot = new LinkedHashMap<>();
        boolean isOre = defaultState.is(BlockTags.COAL_ORES)
            || defaultState.is(BlockTags.IRON_ORES)
            || defaultState.is(BlockTags.COPPER_ORES)
            || defaultState.is(BlockTags.GOLD_ORES)
            || defaultState.is(BlockTags.REDSTONE_ORES)
            || defaultState.is(BlockTags.LAPIS_ORES)
            || defaultState.is(BlockTags.DIAMOND_ORES)
            || defaultState.is(BlockTags.EMERALD_ORES);

        if (isOre) {
            loot.put("template", "default:loot_table/ore");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("ore_drop", BuiltInRegistries.ITEM.getKey(registeredBlock.asItem()).toString());
            args.put("ore_block", BuiltInRegistries.BLOCK.getKey(registeredBlock).toString());
            loot.put("arguments", args);
        } else {
            loot.put("template", "default:loot_table/self");
        }
        blockConfig.put("loot", loot);

        return blockConfig;
    }

    private static Map<String, Object> toPropertyDefinition(Property<?> property, BlockState defaultState) {
        Map<String, Object> out = new LinkedHashMap<>();

        if (property instanceof BooleanProperty) {
            out.put("type", "boolean");
        } else if (property instanceof IntegerProperty intProperty) {
            out.put("type", "int");
            int min = intProperty.getPossibleValues().stream().min(Integer::compareTo).orElse(0);
            int max = intProperty.getPossibleValues().stream().max(Integer::compareTo).orElse(0);
            out.put("min", min);
            out.put("max", max);
        } else {
            out.put("type", "string");
        }

        out.put("default", safePropertyValue(defaultState, property));
        return out;
    }

    private static String variantKey(BlockState state, Collection<Property<?>> properties) {
        return properties.stream()
            .map(prop -> prop.getName() + "=" + safePropertyValue(state, prop))
            .collect(Collectors.joining(","));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String safePropertyValue(BlockState state, Property property) {
        try {
            Object value = state.getValue(property);
            return String.valueOf(value).toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String formatBlockState(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Collection<Property<?>> properties = state.getProperties();
        if (properties.isEmpty()) {
            return blockId;
        }
        String serialized = properties.stream()
            .map(prop -> prop.getName() + "=" + safePropertyValue(state, prop))
            .sorted()
            .collect(Collectors.joining(","));
        return blockId + "[" + serialized + "]";
    }

    private static List<String> inferTags(BlockState state) {
        List<String> tags = new ArrayList<>();
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) tags.add("minecraft:mineable/pickaxe");
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) tags.add("minecraft:mineable/axe");
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) tags.add("minecraft:mineable/shovel");
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) tags.add("minecraft:mineable/hoe");
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) tags.add("minecraft:needs_stone_tool");
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) tags.add("minecraft:needs_iron_tool");
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) tags.add("minecraft:needs_diamond_tool");
        return tags;
    }
}
