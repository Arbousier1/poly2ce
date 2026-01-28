package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import xyz.nucleoid.packettweaker.PacketContext;
import net.minecraft.block.*;

import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import com.mojang.authlib.GameProfile;

import java.util.*;
import java.util.stream.Collectors;

public class BlockConverterLogic {

    public static Map<String, Object> convert(PolymerBlock polymerBlock) {
        Map<String, Object> blockConfig = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();

        Block block = (Block) polymerBlock;
        BlockState defaultState = block.getDefaultState();
        
        // 1. 创建安全上下文环境
        ServerPlayerEntity fakePlayer = createSafeFakePlayer();
        PacketContext ctx = fakePlayer != null ? PacketContext.create(fakePlayer) : PacketContext.create();

        try {
            // --- A. 基础材质与属性 ---
            BlockState visualState = PolymerBlockUtils.getPolymerBlockState(defaultState, ctx);
            Block visualBlock = visualState.getBlock();
            
            // [Enhanced] 提取完整方块设置 (包含 Support Shape, Custom Data 等)
            extractBlockSettings(block, defaultState, visualBlock, settings);

            // --- B. Polymer 特有逻辑 ---
            try {
                if (ctx.getPlayer() != null) {
                    polymerBlock.onPolymerBlockSend(defaultState, BlockPos.ORIGIN.mutableCopy(), ctx.asNotNullWithPlayer());
                }
            } catch (Exception ignored) {}

            BlockState breakState = polymerBlock.getPolymerBreakEventBlockState(defaultState, ctx);
            if (breakState != null && !breakState.getBlock().equals(visualBlock)) {
                settings.put("break-state", Registries.BLOCK.getId(breakState.getBlock()).toString());
            }

            if (polymerBlock.forceLightUpdates(defaultState)) {
                settings.put("force-light-updates", true);
            }
            
            // --- C. 方块实体支持 (Block Entity & Renderer) ---
            if (block instanceof BlockEntityProvider) {
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
                } else if (block instanceof BedBlock) { // 新增 Bed 支持
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

            // --- D. 剔除优化 (Culling) [New] ---
            // 如果视觉方块是完整的，CraftEngine 可以开启剔除优化
            if (visualState.isOpaqueFullCube()) {
                Map<String, Object> cullingData = new LinkedHashMap<>();
                cullingData.put("occlude", true);
                settings.put("culling", cullingData);
            }

            // --- E. 方块状态 (States & Properties) ---
            StateManager<Block, BlockState> stateManager = block.getStateManager();
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
                
                for (BlockState state : stateManager.getStates()) {
                    String variantKey = getVariantKey(state, properties);
                    BlockState subVisualState = PolymerBlockUtils.getBlockStateSafely(polymerBlock, state, ctx);
                    String subVisualBlockId = Registries.BLOCK.getId(subVisualState.getBlock()).toString();
                    
                    Map<String, Object> appearance = new LinkedHashMap<>();
                    // 智能判断模型生成策略
                    if (shouldUseCustomModel(subVisualState.getBlock())) {
                         Map<String, Object> modelConfig = new LinkedHashMap<>();
                         modelConfig.put("template", "default:model/cube");
                         Map<String, Object> args = new LinkedHashMap<>();
                         String baseName = Registries.BLOCK.getId(subVisualState.getBlock()).getPath();
                         args.put("model", "minecraft:block/" + baseName);
                         
                         // 自动填充基础纹理 (假设标准命名)
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
                    String skinValue = headBlock.getPolymerSkinValue(defaultState, BlockPos.ORIGIN, ctx);
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
                } catch (Exception ignored) {}
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
            } else if (block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock) {
                events.put("on-interact", Arrays.asList("handle_door_toggle"));
            } else if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                events.put("on-interact", Arrays.asList("open_inventory"));
            } else if (block instanceof TntBlock) {
                events.put("on-interact", Arrays.asList("ignite_tnt"));
            }
            if (!events.isEmpty()) {
                blockConfig.put("events", events);
            }

            // --- I. 掉落表配置 ---
            Map<String, Object> lootConfig = new LinkedHashMap<>();
            // 检测是否为矿石 (通用 Tag 匹配)
            boolean isOre = defaultState.isIn(BlockTags.COAL_ORES) || 
                            defaultState.isIn(BlockTags.IRON_ORES) ||
                            defaultState.isIn(BlockTags.COPPER_ORES) ||
                            defaultState.isIn(BlockTags.GOLD_ORES) ||
                            defaultState.isIn(BlockTags.REDSTONE_ORES) ||
                            defaultState.isIn(BlockTags.LAPIS_ORES) ||
                            defaultState.isIn(BlockTags.DIAMOND_ORES) ||
                            defaultState.isIn(BlockTags.EMERALD_ORES);

            if (isOre || block instanceof RedstoneOreBlock) {
                 lootConfig.put("template", "default:loot_table/ore");
                 Map<String, Object> args = new LinkedHashMap<>();
                 args.put("ore_drop", Registries.ITEM.getId(block.asItem()).toString());
                 args.put("ore_block", Registries.BLOCK.getId(block).toString());
                 lootConfig.put("arguments", args);
            } else {
                 lootConfig.put("template", "default:loot_table/self");
            }
            blockConfig.put("loot", lootConfig);

            if (settings.get("material") == null) {
                throw new IllegalStateException("Material detection failed");
            }
            blockConfig.put("settings", settings);
            if (!stateSection.isEmpty()) {
                blockConfig.put("state", stateSection);
            }

        } catch (Exception e) {
            e.printStackTrace();
            blockConfig.put("_error", "Conversion failed: " + e.getMessage());
            settings.put("material", "barrier");
            blockConfig.put("settings", settings);
        }

        return blockConfig;
    }

    // --- 核心逻辑提取方法 ---

    private static void extractBlockSettings(Block block, BlockState state, Block visualBlock, Map<String, Object> settings) {
        // 1. 基础材质
        settings.put("material", Registries.BLOCK.getId(visualBlock).toString());

        // 2. 挖掘属性
        settings.put("hardness", block.getHardness());
        settings.put("resistance", block.getBlastResistance());
        
        // 3. 光照
        if (state.getLuminance() > 0) {
            settings.put("luminance", state.getLuminance());
        }

        // 4. 地图颜色
        try {
            //noinspection deprecation
            settings.put("map-color", state.getMapColor(null, null).id);
        } catch (Exception ignored) {}

        // 5. 物理与运动
        settings.put("friction", block.getSlipperiness());
        settings.put("speed-factor", block.getVelocityMultiplier()); 
        settings.put("jump-factor", block.getJumpVelocityMultiplier());

        // 6. 逻辑属性
        settings.put("is-randomly-ticking", state.hasRandomTicks());
        
        // [New] Support Shape (碰撞箱/支撑形状)
        // 使用视觉方块 ID 作为支撑形状的基础，这有助于 CraftEngine 处理放置逻辑
        settings.put("support-shape", Registries.BLOCK.getId(visualBlock).toString());

        // [New] Custom Data (自定义数据占位)
        if (block instanceof NoteBlock) {
            Map<String, Object> customData = new LinkedHashMap<>();
            customData.put("note", 0); // 默认值
            settings.put("custom-data", customData);
        }

        // 燃烧属性
        if (visualBlock == Blocks.TNT || state.isIn(BlockTags.WOOL) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) { 
             settings.put("burnable", true); 
             // 可以进一步添加 burn-chance 如果有数据源
        }

        // 7. 碰撞与交互
        //noinspection deprecation
        settings.put("replaceable", state.isReplaceable());
        //noinspection deprecation
        settings.put("push-reaction", state.getPistonBehavior().name().toLowerCase());
        
        // Advanced CraftEngine settings
        settings.put("require-correct-tools", true);
        settings.put("respect-tool-component", false);
        settings.put("incorrect-tool-speed", 0.3f);
        try {
            settings.put("instrument", state.getInstrument().ordinal());
        } catch (Exception ignored) {
            settings.put("instrument", 0); // HARP default
        }
        settings.put("fluid-state", state.getFluidState() != null && !state.getFluidState().isEmpty());
        settings.put("propagates-skylight-down", state.hasSidedTransparency());

        try {
            settings.put("is-redstone-conductor", state.isSolidBlock(FakeWorld.INSTANCE_UNSAFE, BlockPos.ORIGIN));
            settings.put("is-suffocating", state.shouldSuffocate(FakeWorld.INSTANCE_UNSAFE, BlockPos.ORIGIN));
            settings.put("is-view-blocking", state.isOpaqueFullCube());
        } catch (Exception ignored) {
            settings.put("is-redstone-conductor", true);
            settings.put("is-suffocating", true);
            settings.put("is-view-blocking", true);
        }

        // 8. 工具需求
        Set<String> correctTools = new HashSet<>();
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) correctTools.add("minecraft:pickaxe");
        if (state.isIn(BlockTags.AXE_MINEABLE)) correctTools.add("minecraft:axe");
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) correctTools.add("minecraft:shovel");
        if (state.isIn(BlockTags.HOE_MINEABLE)) correctTools.add("minecraft:hoe");
        
        // 补充工具等级需求
        if (state.isIn(BlockTags.NEEDS_DIAMOND_TOOL)) correctTools.add("minecraft:diamond_tier");
        if (state.isIn(BlockTags.NEEDS_IRON_TOOL)) correctTools.add("minecraft:iron_tier");
        
        if (!correctTools.isEmpty()) {
            settings.put("correct-tools", correctTools);
        }

        // 9. 声音系统
        Map<String, String> sounds = new LinkedHashMap<>();
        String blockIdPath = Registries.BLOCK.getId(visualBlock).getPath();
        sounds.put("break", "minecraft:block." + blockIdPath + ".break");
        sounds.put("place", "minecraft:block." + blockIdPath + ".place");
        sounds.put("hit", "minecraft:block." + blockIdPath + ".hit");
        sounds.put("step", "minecraft:block." + blockIdPath + ".step");
        sounds.put("fall", "minecraft:block." + blockIdPath + ".fall");
        sounds.put("ambient", "minecraft:block." + blockIdPath + ".ambient");
        sounds.put("land", "minecraft:block." + blockIdPath + ".land");
        sounds.put("destroy", "minecraft:block." + blockIdPath + ".break");
        settings.put("sounds", sounds);

        // 10. 标签
        List<String> tags = inferToolTags(state);
        if (!tags.isEmpty()) {
            settings.put("tags", tags);
        }
    }

    private static Map<String, Object> createPropertyDefinition(Property<?> property, BlockState defaultState) {
        Map<String, Object> def = new LinkedHashMap<>();
        
        String type = "string";
        String name = property.getName();
        
        if (property instanceof IntProperty) type = "int";
        else if (property instanceof BooleanProperty) type = "boolean";
        else if (property instanceof EnumProperty) {
            if (property.getType() == Direction.class) {
                if (name.equals("axis")) {
                    type = "axis";
                } else {
                    type = isFullDirection(property) ? "direction" : "horizontal_direction";
                }
            } else {
                if (name.equals("half")) type = "double_block_half";
                else if (name.equals("shape")) type = "stairs_shape";
                else if (name.equals("hinge")) type = "hinge";
                // [Enhanced] 更多 CraftEngine 映射
                else if (name.equals("slab_type")) type = "slab_type";
                else type = "string";
            }
        }
        
        def.put("type", type);
        
        if (property instanceof IntProperty intProp) {
            int min = intProp.getValues().stream().min(Integer::compareTo).orElse(0);
            int max = intProp.getValues().stream().max(Integer::compareTo).orElse(1);
            def.put("range", min + "~" + max);
        } else {
            def.put("range", getSafeRange(property));
        }
        
        def.put("default", getSafeValue(defaultState, property));
        return def;
    }

    // --- 安全工具方法 ---

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getSafeValue(BlockState state, Property<?> property) {
        try {
            Property<T> typedProperty = (Property<T>) property;
            if (!state.contains(typedProperty)) return "null";
            T value = state.get(typedProperty);
            return value != null ? value.toString().toLowerCase() : "null";
        } catch (Exception e) {
            return "null";
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getSafeRange(Property<?> property) {
        try {
            Property<T> typedProperty = (Property<T>) property;
            return typedProperty.getValues().stream()
                    .map(v -> v == null ? "null" : v.toString().toLowerCase())
                    .collect(Collectors.joining(","));
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isFullDirection(Property<?> property) {
        try {
            Collection<Direction> values = (Collection<Direction>) property.getValues();
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

    private static ServerPlayerEntity createSafeFakePlayer() {
        try {
            return new ServerPlayerEntity(
                (MinecraftServer) null,
                (ServerWorld) FakeWorld.INSTANCE_UNSAFE,
                new GameProfile(UUID.randomUUID(), "PolymerBlockConverter"),
                SyncedClientOptions.createDefault()
            ) {
                @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> inferToolTags(BlockState state) {
        List<String> tags = new ArrayList<>();
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) tags.add("minecraft:mineable/pickaxe");
        if (state.isIn(BlockTags.AXE_MINEABLE)) tags.add("minecraft:mineable/axe");
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) tags.add("minecraft:mineable/shovel");
        if (state.isIn(BlockTags.HOE_MINEABLE)) tags.add("minecraft:mineable/hoe");
        if (state.isIn(BlockTags.NEEDS_DIAMOND_TOOL)) tags.add("minecraft:needs_diamond_tool");
        if (state.isIn(BlockTags.NEEDS_IRON_TOOL)) tags.add("minecraft:needs_iron_tool");
        if (state.isIn(BlockTags.NEEDS_STONE_TOOL)) tags.add("minecraft:needs_stone_tool");
        return tags;
    }

    private static List<Map<String, Object>> inferBehaviors(Block block, PolymerBlock polymerBlock) {
        List<Map<String, Object>> list = new ArrayList<>();
        
        if (block instanceof FallingBlock) list.add(Map.of("type", "falling_block"));
        if (block instanceof CropBlock) list.add(Map.of("type", "crop_block"));
        if (block instanceof DoorBlock) list.add(Map.of("type", "door_block"));
        if (block instanceof TrapdoorBlock) list.add(Map.of("type", "trapdoor_block"));
        if (block instanceof SlabBlock) list.add(Map.of("type", "slab_block"));
        if (block instanceof StairsBlock) list.add(Map.of("type", "stairs_block"));
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
        
        // [New] 额外行为
        if (block instanceof BedBlock) list.add(Map.of("type", "bed_block"));
        
        if (polymerBlock instanceof PolymerHeadBlock) {
            list.add(Map.of("type", "bush_block")); 
        }
        
        return list;
    }
}