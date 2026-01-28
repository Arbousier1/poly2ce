package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import eu.pb4.polymer.core.api.other.PacketContext;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import com.mojang.authlib.GameProfile;

import java.util.*;
import java.util.stream.Collectors;

public class BlockConverterLogic {

    public static Map<String, Object> convert(PolymerBlock polymerBlock) {
        Map<String, Object> blockConfig = new LinkedHashMap<>();
        Map<String, Object> settings = new LinkedHashMap<>();

        Block block = (Block) polymerBlock;
        BlockState defaultState = block.getDefaultState();
        
        // 创建一个安全的虚拟环境
        ServerPlayerEntity fakePlayer = createSafeFakePlayer();
        PacketContext ctx = PacketContext.create(fakePlayer);

        try {
            // --- A. 基础材质与属性 (Settings) ---
            
            // [FIX 1] 使用 getPolymerBlockState 获取准确的视觉方块
            BlockState visualState = PolymerBlockUtils.getPolymerBlockState(defaultState, ctx);
            Block visualBlock = visualState.getBlock();
            
            String materialId = Registries.BLOCK.getId(visualBlock).toString();
            settings.put("material", materialId);
            
            // 物理属性
            settings.put("hardness", block.getHardness());
            settings.put("resistance", block.getBlastResistance());
            if (defaultState.getLuminance() > 0) {
                settings.put("luminance", defaultState.getLuminance());
            }

            // 地图颜色
            try {
                settings.put("map-color", defaultState.getMapColor(null, null).id);
            } catch (Exception ignored) {}

            if (defaultState.isReplaceable()) settings.put("replaceable", true);
            settings.put("push-reaction", defaultState.getPistonBehavior().name().toLowerCase());

            // 声音配置
            Map<String, String> sounds = new LinkedHashMap<>();
            String blockIdPath = Registries.BLOCK.getId(visualBlock).getPath();
            sounds.put("break", "minecraft:block." + blockIdPath + ".break");
            sounds.put("place", "minecraft:block." + blockIdPath + ".place");
            sounds.put("hit", "minecraft:block." + blockIdPath + ".hit");
            sounds.put("step", "minecraft:block." + blockIdPath + ".step");
            settings.put("sounds", sounds);

            // 工具标签推断
            List<String> tags = inferToolTags(defaultState);
            if (!tags.isEmpty()) {
                settings.put("tags", tags);
            }

            // --- [FIX 2] 方块实体数据探测 ---
            // 尝试触发 Polymer 的发送逻辑，虽然我们无法直接拦截包，
            // 但这能确保方块内部状态被正确初始化（部分 Polymer 实现依赖此步骤）
            try {
                polymerBlock.onPolymerBlockSend(defaultState, BlockPos.ORIGIN.toMutable(), new PacketContext.NotNullWithPlayer(ctx) {
                    @Override
                    public ServerPlayerEntity getPlayer() {
                        return fakePlayer; // 使用我们的虚拟玩家，避免 null
                    }
                });
            } catch (Exception ignored) {
                // 忽略非关键错误，静态导出无法完全模拟网络层
            }

            // --- B. 方块状态 (State & Properties) ---
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
                    
                    // 获取该状态下的视觉 BlockState
                    BlockState subVisualState = PolymerBlockUtils.getBlockStateSafely(polymerBlock, state, ctx);
                    String subVisualBlockId = Registries.BLOCK.getId(subVisualState.getBlock()).toString();
                    
                    Map<String, Object> appearance = new LinkedHashMap<>();
                    appearance.put("auto-state", subVisualBlockId);
                    
                    appearances.put(variantKey, appearance);
                    
                    Map<String, Object> variant = new LinkedHashMap<>();
                    variant.put("appearance", variantKey);
                    variants.put(variantKey, variant);
                }
                
                stateSection.put("appearances", appearances);
                stateSection.put("variants", variants);
            }

            // --- C. 特殊方块处理 (PolymerHeadBlock) ---
            if (polymerBlock instanceof PolymerHeadBlock headBlock) {
                settings.put("material", "player_head");
                try {
                    String skinValue = headBlock.getPolymerSkinValue(defaultState, BlockPos.ORIGIN, ctx);
                    if (skinValue != null && !skinValue.isEmpty()) {
                        List<Map<String, Object>> clientData = new ArrayList<>();
                        Map<String, Object> op = new LinkedHashMap<>();
                        op.put("type", "SET");
                        op.put("path", "SkullOwner");
                        
                        Map<String, Object> profile = new LinkedHashMap<>();
                        Map<String, Object> props = new LinkedHashMap<>();
                        List<Map<String, Object>> textures = new ArrayList<>();
                        Map<String, Object> tex = new LinkedHashMap<>();
                        tex.put("Value", skinValue);
                        textures.add(tex);
                        props.put("textures", textures);
                        profile.put("properties", props);
                        
                        op.put("value", profile);
                        clientData.add(op);
                        settings.put("client-bound-data", clientData);
                    }
                } catch (Exception ignored) {}
            }

            // --- D. 行为推断 (Behaviors) ---
            List<Map<String, Object>> behaviors = inferBehaviors(block, polymerBlock);
            if (!behaviors.isEmpty()) {
                blockConfig.put("behaviors", behaviors);
            }

            // --- 验证与组装 ---
            if (settings.get("material") == null) {
                throw new IllegalStateException("Material detection failed");
            }

            blockConfig.put("settings", settings);
            if (!stateSection.isEmpty()) {
                blockConfig.put("state", stateSection);
            }

        } catch (Exception e) {
            blockConfig.put("_error", "Conversion failed: " + e.getMessage());
            settings.put("material", "barrier");
            blockConfig.put("settings", settings);
        }

        return blockConfig;
    }

    // --- 辅助方法 ---

    private static ServerPlayerEntity createSafeFakePlayer() {
        try {
            return new ServerPlayerEntity(
                (MinecraftServer) null,
                FakeWorld.INSTANCE_UNSAFE,
                new GameProfile(UUID.randomUUID(), "PolymerBlockConverter"),
                null
            ) {
                @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> createPropertyDefinition(Property<?> property, BlockState defaultState) {
        Map<String, Object> def = new LinkedHashMap<>();
        
        String type = "string";
        String name = property.getName();
        
        if (property instanceof IntProperty) type = "int";
        else if (property instanceof BooleanProperty) type = "boolean";
        else if (property instanceof DirectionProperty) {
            if (name.equals("facing")) type = "horizontal_direction"; 
            else if (name.equals("axis")) type = "axis";
            else type = "direction";
        } 
        else if (property instanceof EnumProperty) {
            if (name.equals("facing")) type = "4-direction";
            else if (name.equals("half")) type = "double_block_half";
            else if (name.equals("shape")) type = "stairs_shape";
            else if (name.equals("hinge")) type = "hinge";
            else type = "string";
        }
        
        def.put("type", type);
        
        if (property instanceof IntProperty intProp) {
            def.put("range", intProp.getMin() + "~" + intProp.getMax());
        } else {
            String range = property.getValues().stream()
                    .map(v -> v.toString().toLowerCase())
                    .collect(Collectors.joining(","));
            def.put("range", range);
        }
        
        def.put("default", defaultState.get(property).toString().toLowerCase());
        return def;
    }

    private static String getVariantKey(BlockState state, Collection<Property<?>> properties) {
        return properties.stream()
                .map(prop -> prop.getName() + "=" + state.get(prop).toString().toLowerCase())
                .collect(Collectors.joining(","));
    }

    private static List<String> inferToolTags(BlockState state) {
        List<String> tags = new ArrayList<>();
        if (state.isIn(BlockTags.PICKAXE_MINEABLE)) tags.add("minecraft:mineable/pickaxe");
        if (state.isIn(BlockTags.AXE_MINEABLE)) tags.add("minecraft:mineable/axe");
        if (state.isIn(BlockTags.SHOVEL_MINEABLE)) tags.add("minecraft:mineable/shovel");
        if (state.isIn(BlockTags.HOE_MINEABLE)) tags.add("minecraft:mineable/hoe");
        return tags;
    }

    // [FIX 3] 扩展更多行为类型
    private static List<Map<String, Object>> inferBehaviors(Block block, PolymerBlock polymerBlock) {
        List<Map<String, Object>> list = new ArrayList<>();
        
        // 基础类型
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
        
        // 容器与功能性方块
        if (block instanceof SignBlock) list.add(Map.of("type", "sign_block"));
        if (block instanceof ChestBlock) list.add(Map.of("type", "storage_block"));
        if (block instanceof RedstoneTorchBlock) list.add(Map.of("type", "redstone_torch_block"));
        
        // 特殊 Polymer 类型
        if (polymerBlock instanceof PolymerHeadBlock) {
            list.add(Map.of("type", "bush_block"));
        }
        
        return list;
    }
}