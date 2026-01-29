package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import xyz.nucleoid.packettweaker.PacketContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.*;

import com.mojang.authlib.GameProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings({"null", "unchecked", "unused"})
public class BlockConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerBlockConverter");
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /**
     * 转换方块逻辑
     * @param registeredBlock 实际注册在游戏中的方块对象
     * @param polymerBlock    Polymer 逻辑接口
     * @param level           服务器世界上下文
     */
    public static Map<String, Object> convert(Block registeredBlock, PolymerBlock polymerBlock, ServerLevel level) {
        Map<String, Object> blockConfig = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();

        Block block = registeredBlock;

        try {
            // 检查注册状态
            boolean isSynced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block) != null;
            boolean isInstance = block instanceof PolymerBlock;

            if (!isSynced && !isInstance) {
                LOGGER.warn("Block {} appears not to be registered with Polymer correctly (No Sync/Interface)", 
                    BuiltInRegistries.BLOCK.getKey(block));
            }

            BlockState defaultState = block.defaultBlockState();

            // =================================================================================
            // [关键修复] 上下文创建与视觉状态获取 (带降级保护)
            // =================================================================================
            PacketContext ctx;
            BlockState visualState;
            
            try {
                // 方案 A: 尝试使用 FakePlayer (为了获取最佳视觉效果)
                ServerPlayer fakePlayer = createSafeFakePlayer(level);
                if (fakePlayer != null) {
                    ctx = PacketContext.create(fakePlayer);
                    // 这里可能会抛出 NPE (如果 mod 尝试访问 player.connection)
                    visualState = PolymerBlockUtils.getPolymerBlockState(defaultState, ctx);
                } else {
                    throw new IllegalStateException("FakePlayer creation failed");
                }
            } catch (Exception e) {
                // 方案 B: 降级为无玩家上下文 (解决 handler is null 问题)
                // LOGGER.debug("Context fallback for {}: {}", BuiltInRegistries.BLOCK.getKey(block), e.getMessage());
                
                // 创建一个没有任何玩家绑定的上下文
                ctx = PacketContext.create(); 
                
                try {
                    visualState = PolymerBlockUtils.getPolymerBlockState(defaultState, ctx);
                } catch (Exception e2) {
                    // 方案 C: 彻底失败，直接使用原始状态
                    LOGGER.warn("Visual state fetch completely failed for {}: {}", BuiltInRegistries.BLOCK.getKey(block), e2.getMessage());
                    visualState = defaultState;
                }
            }
            
            Block visualBlock = visualState.getBlock();
            // =================================================================================

            LOGGER.info("Converting Polymer block: {} -> {}", BuiltInRegistries.BLOCK.getKey(block), BuiltInRegistries.BLOCK.getKey(visualBlock));

            // 提取完整方块设置
            extractBlockSettings(block, defaultState, visualBlock, settings);

            // --- B. Polymer 特有逻辑 ---
            // 注意：这里需要判空，因为如果降级到了 方案B，ctx.getPlayer() 是 null
            if (ctx.getPlayer() != null) {
                try {
                    // 使用 polymerBlock 接口触发发送逻辑
                    polymerBlock.onPolymerBlockSend(defaultState, BlockPos.ZERO.mutable(), ctx.asNotNullWithPlayer());
                } catch (Exception e) {
                    LOGGER.debug("onPolymerBlockSend failed: {}", e.getMessage());
                }
            }

            // 获取破坏状态 (同样需要异常保护)
            try {
                BlockState breakState = polymerBlock.getPolymerBreakEventBlockState(defaultState, ctx);
                if (breakState != null && !breakState.getBlock().equals(visualBlock)) {
                    settings.put("break-state", BuiltInRegistries.BLOCK.getKey(breakState.getBlock()).toString());
                }
            } catch (Exception ignored) {}

            if (polymerBlock.forceLightUpdates(defaultState)) {
                settings.put("force-light-updates", true);
            }

            // --- C. 方块实体支持 (Block Entity & Renderer) ---
            if (block instanceof EntityBlock) {
                Map<String, Object> blockEntityConfig = new LinkedHashMap<>();
                String type;
                String renderer;

                if (block instanceof ChestBlock) {
                    type = "chest";
                    renderer = "chest";
                } else if (block instanceof SignBlock) {
                    type = "sign";
                    renderer = "sign";
                } else if (block instanceof SpawnerBlock) {
                    type = "spawner";
                    renderer = "spawner";
                } else if (block instanceof ShulkerBoxBlock) {
                    type = "shulker_box";
                    renderer = "shulker_box";
                } else if (block instanceof BedBlock) {
                    type = "bed";
                    renderer = "bed";
                } else {
                    type = "custom";
                    renderer = "custom";
                }

                blockEntityConfig.put("type", type);
                blockEntityConfig.put("renderer", renderer);
                settings.put("block-entity", blockEntityConfig);
            }

            // --- D. 剔除优化 (Culling) ---
            // 使用 FakeWorld.INSTANCE_UNSAFE 进行物理检查是安全的，不需要 Player
            if (visualState.isRedstoneConductor(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO)) {
                Map<String, Object> cullingData = new LinkedHashMap<>();
                cullingData.put("occlude", true);
                settings.put("culling", cullingData);
            }

            // --- E. 方块状态 (States & Properties) ---
            StateDefinition<Block, BlockState> stateManager = block.getStateDefinition();
            Collection<Property<?>> properties = stateManager.getProperties();
            Map<String, Object> stateSection = new LinkedHashMap<>();

            if (!properties.isEmpty()) {
                Map<String, Object> propConfig = new LinkedHashMap<>();
                for (Property<?> prop : properties) {
                    propConfig.put(prop.getName(), createPropertyDefinition(prop, defaultState));
                }
                stateSection.put("properties", propConfig);

                Map<String, Object> appearances = new LinkedHashMap<>();
                Map<String, Object> variants = new LinkedHashMap<>();

                for (BlockState state : stateManager.getPossibleStates()) {
                    String variantKey = getVariantKey(state, properties);
                    
                    // 获取子状态 (带异常保护，使用当前有效的 ctx)
                    BlockState subVisualState;
                    try {
                        subVisualState = PolymerBlockUtils.getBlockStateSafely(  
                            polymerBlock, state, PolymerBlockUtils.NESTED_DEFAULT_DISTANCE, ctx  
                        );
                    } catch (Exception e) {
                        subVisualState = visualState; // 如果失败，回退到基础视觉状态
                    }
                    
                    String subVisualBlockId = BuiltInRegistries.BLOCK.getKey(subVisualState.getBlock()).toString();

                    Map<String, Object> appearance = new LinkedHashMap<>();

                    if (shouldUseCustomModel(subVisualState.getBlock())) {
                        Map<String, Object> modelConfig = new LinkedHashMap<>();
                        modelConfig.put("template", "default:model/cube");

                        Map<String, Object> args = new LinkedHashMap<>();
                        String baseName = BuiltInRegistries.BLOCK.getKey(subVisualState.getBlock()).getPath();
                        args.put("model", "minecraft:block/" + baseName);

                        Map<String, String> textures = new LinkedHashMap<>();
                        textures.put("all", "minecraft:block/" + baseName);
                        args.put("textures", textures);

                        modelConfig.put("arguments", args);
                        appearance.put("model", modelConfig);
                    } else {
                        appearance.put("auto-state", subVisualBlockId);
                    }

                    appearances.put(variantKey, appearance);

                    Map<String, Object> variant = new LinkedHashMap<>();
                    variant.put("appearance", variantKey);
                    variants.put(variantKey, variant);
                }

                stateSection.put("appearances", appearances);
                stateSection.put("variants", variants);
            }

            // --- F. 特殊方块处理 (Head Block) ---
            if (polymerBlock instanceof PolymerHeadBlock headBlock) {
                settings.put("material", "player_head");
                try {
                    String skinValue = headBlock.getPolymerSkinValue(defaultState, BlockPos.ZERO, ctx);
                    if (skinValue != null && !skinValue.isEmpty()) {
                        List<Map<String, Object>> clientData = new ArrayList<>();
                        Map<String, Object> profile = new LinkedHashMap<>();
                        Map<String, Object> props = new LinkedHashMap<>();
                        List<Map<String, Object>> textures = new ArrayList<>();
                        Map<String, Object> tex = new LinkedHashMap<>();
                        tex.put("Value", skinValue);
                        textures.add(tex);
                        props.put("textures", textures);
                        profile.put("properties", props);

                        Map<String, Object> op = new LinkedHashMap<>();
                        op.put("type", "SET");
                        op.put("path", "SkullOwner");
                        op.put("value", profile);
                        clientData.add(op);

                        settings.put("client-bound-data", clientData);
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to get head skin value: {}", e.getMessage());
                }
            }

            // --- G. 行为推断 ---
            List<Map<String, Object>> behaviors = inferBehaviors(block, polymerBlock);
            if (!behaviors.isEmpty()) {
                blockConfig.put("behaviors", behaviors);
            }

            // --- H. 事件系统 ---
            Map<String, Object> events = new LinkedHashMap<>();
            if (block instanceof ButtonBlock || block instanceof LeverBlock) {
                events.put("on-interact", Arrays.asList("handle_redstone_toggle"));
            } else if (block instanceof DoorBlock || block instanceof TrapDoorBlock ||
                block instanceof FenceGateBlock) {
                events.put("on-interact", Arrays.asList("handle_door_toggle"));
            } else if (block instanceof ChestBlock || block instanceof BarrelBlock ||
                block instanceof ShulkerBoxBlock) {
                events.put("on-interact", Arrays.asList("open_inventory"));
            } else if (block instanceof TntBlock) {
                events.put("on-interact", Arrays.asList("ignite_tnt"));
            }

            if (!events.isEmpty()) {
                blockConfig.put("events", events);
            }

            // --- I. 掉落表配置 ---
            Map<String, Object> lootConfig = new LinkedHashMap<>();

            boolean isOre = defaultState.is(BlockTags.COAL_ORES) ||
                defaultState.is(BlockTags.IRON_ORES) ||
                defaultState.is(BlockTags.COPPER_ORES) ||
                defaultState.is(BlockTags.GOLD_ORES) ||
                defaultState.is(BlockTags.REDSTONE_ORES) ||
                defaultState.is(BlockTags.LAPIS_ORES) ||
                defaultState.is(BlockTags.DIAMOND_ORES) ||
                defaultState.is(BlockTags.EMERALD_ORES);

            if (isOre || block instanceof RedStoneOreBlock) {
                lootConfig.put("template", "default:loot_table/ore");
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("ore_drop", BuiltInRegistries.ITEM.getKey(block.asItem()).toString());
                args.put("ore_block", BuiltInRegistries.BLOCK.getKey(block).toString());
                lootConfig.put("arguments", args);
            } else {
                lootConfig.put("template", "default:loot_table/self");
            }
            blockConfig.put("loot", lootConfig);

            // 最终验证
            if (settings.get("material") == null) {
                throw new IllegalStateException("Material detection failed");
            }

            blockConfig.put("settings", settings);

            if (!stateSection.isEmpty()) {
                blockConfig.put("state", stateSection);
            }

        } catch (Exception e) {
            LOGGER.error("Block conversion failed for {}",
                BuiltInRegistries.BLOCK.getKey(block), e);
            blockConfig.put("_error", "Conversion failed: " + e.getMessage());
            blockConfig.put("_error_type", e.getClass().getSimpleName());
            blockConfig.put("_stack_trace", getStackTraceString(e));
            settings.put("material", "barrier");
            blockConfig.put("settings", settings);
        }

        return blockConfig;
    }

    // --- 核心逻辑提取方法 ---

    private static void extractBlockSettings(Block block, BlockState state, Block visualBlock,
                                             Map<String, Object> settings) {
        settings.put("material", BuiltInRegistries.BLOCK.getKey(visualBlock).toString());
        settings.put("hardness", block.defaultBlockState().getDestroySpeed(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
        settings.put("resistance", block.getExplosionResistance());

        if (state.getLightEmission() > 0) {
            settings.put("luminance", state.getLightEmission());
        }

        try {
            settings.put("map-color", state.getMapColor(null, null).id);
        } catch (Exception ignored) {}

        settings.put("friction", block.getFriction());
        settings.put("speed-factor", block.getSpeedFactor());
        settings.put("jump-factor", block.getJumpFactor());
        settings.put("is-randomly-ticking", state.isRandomlyTicking());
        settings.put("support-shape", BuiltInRegistries.BLOCK.getKey(visualBlock).toString());

        if (block instanceof NoteBlock) {
            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("note", 0);
            settings.put("custom-data", customData);
        }

        if (visualBlock == Blocks.TNT || state.is(BlockTags.WOOL) ||
            state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) {
            settings.put("burnable", true);
        }

        settings.put("replaceable", state.canBeReplaced());
        settings.put("push-reaction", state.getPistonPushReaction().name().toLowerCase());
        settings.put("require-correct-tools", true);
        settings.put("respect-tool-component", false);
        settings.put("incorrect-tool-speed", 0.3f);

        try {
            settings.put("instrument", state.instrument().ordinal());
        } catch (Exception ignored) {
            settings.put("instrument", 0);
        }

        settings.put("fluid-state", state.getFluidState() != null && !state.getFluidState().isEmpty());
        settings.put("propagates-skylight-down", state.useShapeForLightOcclusion());

        try {
            settings.put("is-redstone-conductor", state.isRedstoneConductor(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
            settings.put("is-suffocating", state.isSuffocating(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
            settings.put("is-view-blocking", state.isViewBlocking(FakeWorld.INSTANCE_UNSAFE, BlockPos.ZERO));
        } catch (Exception ignored) {
            settings.put("is-redstone-conductor", true);
            settings.put("is-suffocating", true);
            settings.put("is-view-blocking", true);
        }

        Set<String> correctTools = new HashSet<>();
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) correctTools.add("minecraft:pickaxe");
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) correctTools.add("minecraft:axe");
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) correctTools.add("minecraft:mineable/shovel");
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) correctTools.add("minecraft:hoe");

        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) correctTools.add("minecraft:diamond_tier");
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) correctTools.add("minecraft:iron_tier");

        if (!correctTools.isEmpty()) {
            settings.put("correct-tools", correctTools);
        }

        Map<String, String> sounds = new LinkedHashMap<>();
        String blockIdPath = BuiltInRegistries.BLOCK.getKey(visualBlock).getPath();
        sounds.put("break", "minecraft:block." + blockIdPath + ".break");
        sounds.put("place", "minecraft:block." + blockIdPath + ".place");
        sounds.put("hit", "minecraft:block." + blockIdPath + ".hit");
        sounds.put("step", "minecraft:block." + blockIdPath + ".step");
        sounds.put("fall", "minecraft:block." + blockIdPath + ".fall");
        sounds.put("ambient", "minecraft:block." + blockIdPath + ".ambient");
        sounds.put("land", "minecraft:block." + blockIdPath + ".land");
        sounds.put("destroy", "minecraft:block." + blockIdPath + ".break");
        settings.put("sounds", sounds);

        List<String> tags = inferToolTags(state);
        if (!tags.isEmpty()) {
            settings.put("tags", tags);
        }
    }

    private static Map<String, Object> createPropertyDefinition(Property<?> property, BlockState defaultState) {
        Map<String, Object> def = new LinkedHashMap<>();
        String type = "string";
        String name = property.getName();

        if (property instanceof IntegerProperty) {
            type = "int";
        } else if (property instanceof BooleanProperty) {
            type = "boolean";
        } else if (property instanceof EnumProperty) {
            if (property.getValueClass() == Direction.class) {
                if (name.equals("axis")) {
                    type = "axis";
                } else {
                    type = isFullDirection(property) ? "direction" : "horizontal_direction";
                }
            } else {
                if (name.equals("half")) type = "double_block_half";
                else if (name.equals("shape")) type = "stairs_shape";
                else if (name.equals("hinge")) type = "hinge";
                else if (name.equals("type")) type = "slab_type";
                else type = "string";
            }
        }

        def.put("type", type);

        if (property instanceof IntegerProperty intProp) {
            int min = intProp.getPossibleValues().stream().min(Integer::compareTo).orElse(0);
            int max = intProp.getPossibleValues().stream().max(Integer::compareTo).orElse(1);
            def.put("range", min + "~" + max);
        } else {
            def.put("range", getSafeRange(property));
        }

        def.put("default", getSafeValue(defaultState, property));
        return def;
    }

    // --- 安全工具方法 ---

    private static <T extends Comparable<T>> String getSafeValue(BlockState state, Property<?> property) {
        try {
            Property<T> typedProperty = (Property<T>) property;
            if (!state.hasProperty(typedProperty)) return "null";
            T value = state.getValue(typedProperty);
            return value != null ? value.toString().toLowerCase() : "null";
        } catch (Exception e) {
            return "null";
        }
    }

    private static <T extends Comparable<T>> String getSafeRange(Property<?> property) {
        try {
            Property<T> typedProperty = (Property<T>) property;
            return typedProperty.getPossibleValues().stream()
                .map(v -> v == null ? "null" : v.toString().toLowerCase())
                .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isFullDirection(Property<?> property) {
        try {
            Collection<Direction> values = (Collection<Direction>) property.getPossibleValues();
            return values.contains(Direction.UP) || values.contains(Direction.DOWN);
        } catch (ClassCastException e) {
            return false;
        }
    }

    private static String getVariantKey(BlockState state, Collection<Property<?>> properties) {
        return properties.stream()
            .map(prop -> prop.getName() + "=" + getSafeValue(state, prop))
            .collect(Collectors.joining(","));
    }

    private static boolean shouldUseCustomModel(Block block) {
        return block instanceof ChestBlock ||
            block instanceof DecoratedPotBlock ||
            block instanceof ShulkerBoxBlock ||
            block instanceof BedBlock ||
            block instanceof SkullBlock;
    }

    @SuppressWarnings("resource")
    private static ServerPlayer createSafeFakePlayer(ServerLevel world) {
        try {
            if (world == null) {
                LOGGER.error("Cannot create fake player: provided ServerLevel is null");
                return null;
            }

            return new ServerPlayer(
                world.getServer(),
                world,
                new GameProfile(NIL_UUID, "PolymerBlockConverter"),
                ClientInformation.createDefault()
            ) {
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isCreative() { return false; }
            };
        } catch (Exception e) {
            LOGGER.error("Failed to create fake player", e);
            return null;
        }
    }

    private static List<String> inferToolTags(BlockState state) {
        List<String> tags = new ArrayList<>();
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) tags.add("minecraft:mineable/pickaxe");
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) tags.add("minecraft:mineable/axe");
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) tags.add("minecraft:mineable/shovel");
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) tags.add("minecraft:mineable/hoe");
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) tags.add("minecraft:needs_diamond_tool");
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) tags.add("minecraft:needs_iron_tool");
        if (state.is(BlockTags.NEEDS_STONE_TOOL)) tags.add("minecraft:needs_stone_tool");
        return tags;
    }

    private static List<Map<String, Object>> inferBehaviors(Block block, PolymerBlock polymerBlock) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (block instanceof FallingBlock) list.add(Map.of("type", "falling_block"));
        if (block instanceof CropBlock) list.add(Map.of("type", "crop_block"));
        if (block instanceof DoorBlock) list.add(Map.of("type", "door_block"));
        if (block instanceof TrapDoorBlock) list.add(Map.of("type", "trapdoor_block"));
        if (block instanceof SlabBlock) list.add(Map.of("type", "slab_block"));
        if (block instanceof StairBlock) list.add(Map.of("type", "stairs_block"));
        if (block instanceof FenceBlock) list.add(Map.of("type", "fence_block"));
        if (block instanceof FenceGateBlock) list.add(Map.of("type", "fence_gate_block"));
        if (block instanceof WallBlock) list.add(Map.of("type", "wall_block"));
        if (block instanceof PressurePlateBlock) list.add(Map.of("type", "pressure_plate_block"));
        if (block instanceof ButtonBlock) list.add(Map.of("type", "button_block"));
        if (block instanceof LeavesBlock) list.add(Map.of("type", "leaves_block"));
        if (block instanceof SignBlock) list.add(Map.of("type", "sign_block"));
        if (block instanceof ChestBlock) list.add(Map.of("type", "storage_block"));
        if (block instanceof RedstoneTorchBlock) list.add(Map.of("type", "redstone_torch_block"));
        if (block instanceof TntBlock) list.add(Map.of("type", "tnt_block"));
        if (block instanceof BedBlock) list.add(Map.of("type", "bed_block"));
        if (polymerBlock instanceof PolymerHeadBlock) {
            list.add(Map.of("type", "bush_block"));
        }
        return list;
    }

    private static String getStackTraceString(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : e.getStackTrace()) {
            sb.append(element.toString()).append("\n");
            if (sb.length() > 500) {
                sb.append("...(truncated)");
                break;
            }
        }
        return sb.toString();
    }
}