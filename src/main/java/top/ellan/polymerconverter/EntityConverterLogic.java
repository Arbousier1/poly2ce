package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.common.impl.entity.InternalEntityHelpers;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import xyz.nucleoid.packettweaker.PacketContext;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.*; // 导入所有元素类型

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket; // Attribute 包
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import com.mojang.datafixers.util.Pair;
import com.mojang.authlib.GameProfile;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;

public class EntityConverterLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerEntityConverter");

    public static Map<String, Object> convert(EntityType<?> entityType) {
        Map<String, Object> furnitureConfig = new LinkedHashMap<>();

        try {
            Entity entity = InternalEntityHelpers.getEntity(entityType);
            if (entity == null) {
                furnitureConfig.put("_error", "Failed to create template entity");
                return furnitureConfig;
            }

            ServerPlayerEntity fakePlayer = createSafeFakePlayer();
            if (fakePlayer == null) {
                furnitureConfig.put("_error", "Failed to create fake player context");
                return furnitureConfig;
            }
            PacketContext ctx = PacketContext.create(fakePlayer);

            List<Map<String, Object>> elements = new ArrayList<>();
            List<Map<String, Object>> hitboxes = new ArrayList<>();

            if (entity instanceof PolymerEntity polymerEntity) {
                LOGGER.info("Converting Polymer entity: {}", Registries.ENTITY_TYPE.getId(entityType));

                // 1. 处理装备栏 (Armor Stand 等)
                List<Pair<EquipmentSlot, ItemStack>> allEquipment = getAllEquipment(entity);
                List<Pair<EquipmentSlot, ItemStack>> visibleEquipment = 
                    polymerEntity.getPolymerVisibleEquipment(allEquipment, fakePlayer);

                for (Pair<EquipmentSlot, ItemStack> pair : visibleEquipment) {
                    if (!pair.getSecond().isEmpty()) {
                        elements.add(createElementFromEquipment(pair.getSecond(), pair.getFirst()));
                    }
                }

                // 2. 处理实体本身的视觉表现 (如 Display Entities)
                EntityType<?> visualType = polymerEntity.getPolymerEntityType(ctx);
                
                // [Enhanced] 属性处理 (Attribute) - 提取 Scale 等
                List<EntityAttributesS2CPacket.Entry> attributes = new ArrayList<>();
                try {
                    polymerEntity.modifyRawEntityAttributeData(attributes, fakePlayer, true);
                } catch (Exception ignored) {}
                
                double entityScale = extractScaleFromAttributes(attributes);

                if (isDisplayEntity(visualType)) {
                    Map<String, Object> displayElement = new LinkedHashMap<>();
                    displayElement.put("position", "0,0,0");
                    
                    if (visualType == EntityType.TEXT_DISPLAY) {
                        displayElement.put("billboard", "center");
                    } else {
                        displayElement.put("billboard", "fixed");
                    }
                    
                    // 应用 Scale 属性
                    if (Math.abs(entityScale - 1.0) > 0.001) {
                        displayElement.put("scale", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", entityScale, entityScale, entityScale));
                    }

                    List<DataTracker.SerializedEntry<?>> trackedData = new ArrayList<>();
                    try {
                        polymerEntity.modifyRawTrackedData(trackedData, fakePlayer, true);
                    } catch (Exception ignored) {}

                    applyTrackedDataToElement(displayElement, trackedData, visualType);
                    
                    // 默认值填充
                    if (visualType == EntityType.ITEM_DISPLAY && !displayElement.containsKey("item")) {
                        displayElement.put("item", "minecraft:barrier");
                    } else if (visualType == EntityType.TEXT_DISPLAY && !displayElement.containsKey("text")) {
                        displayElement.put("text", "Text Display");
                    }
                    elements.add(displayElement);
                }

                // 3. 处理 VirtualElement (ElementHolder API)
                extractElementHolderComponents(entity, elements);
                
                // 4. 生成碰撞箱
                hitboxes.add(createMainHitbox(entity));
            }

            // Fallback
            if (elements.isEmpty()) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("item", "minecraft:barrier"); 
                fallback.put("position", "0,0,0");
                fallback.put("billboard", "fixed");
                fallback.put("scale", "0.5,0.5,0.5");
                elements.add(fallback);
            }

            Map<String, Object> variants = new LinkedHashMap<>();
            Map<String, Object> defaultVariant = new LinkedHashMap<>();
            defaultVariant.put("elements", elements);
            defaultVariant.put("hitboxes", hitboxes);
            variants.put("default", defaultVariant);
            furnitureConfig.put("variants", variants);

            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("placement", "ground");
            settings.put("hit-times", 3);
            furnitureConfig.put("settings", settings);

        } catch (Exception e) {
            LOGGER.error("Entity conversion failed", e);
            furnitureConfig.put("_error", "Conversion error: " + e.getMessage());
        }

        return furnitureConfig;
    }

    // --- 核心转换逻辑 ---

    private static double extractScaleFromAttributes(List<EntityAttributesS2CPacket.Entry> attributes) {
            for (EntityAttributesS2CPacket.Entry entry : attributes) {
                // 在 1.21 中，attribute() 返回 RegistryEntry<EntityAttribute>
                // 注意：EntityAttributes.SCALE 在 1.21 中通常更名为 EntityAttributes.SCALE
                if (entry.attribute().equals(EntityAttributes.SCALE)) {
                    // 修复：1.21 Yarn 中使用 .base() 获取基础数值
                    return entry.base(); 
                }
            }
            return 1.0;
        }

    private static void applyTrackedDataToElement(Map<String, Object> element, List<DataTracker.SerializedEntry<?>> entries, EntityType<?> type) {
        for (DataTracker.SerializedEntry<?> entry : entries) {
            Object value = entry.value();
            
            // JOML Vector3f 处理
            if (value instanceof Vector3f vec) {
                if (Math.abs(vec.x - vec.y) < 0.001 && Math.abs(vec.y - vec.z) < 0.001 && Math.abs(vec.x) > 0.01) {
                    element.put("scale", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vec.x, vec.y, vec.z));
                } else {
                    element.put("translation", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", vec.x, vec.y, vec.z));
                }
            } 
            // JOML Quaternionf 处理
            else if (value instanceof Quaternionf quat) {
                element.put("rotation", String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", quat.x, quat.y, quat.z, quat.w));
            } 
            // ItemStack 处理
            else if (value instanceof ItemStack stack && type == EntityType.ITEM_DISPLAY) {
                writeItemStackData(element, stack);
            } 
            // Text 处理
            else if (value instanceof Text text && type == EntityType.TEXT_DISPLAY) {
                element.put("text", text.getString());
            }
        }
    }

    private static void extractElementHolderComponents(Entity entity, List<Map<String, Object>> elements) {
        try {
            for (Field field : entity.getClass().getDeclaredFields()) {
                if (ElementHolder.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    ElementHolder holder = (ElementHolder) field.get(entity);
                    if (holder != null) {
                        for (VirtualElement virtualElement : holder.getElements()) {
                            Map<String, Object> config = convertVirtualElement(virtualElement);
                            if (config != null) {
                                elements.add(config);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Map<String, Object> convertVirtualElement(VirtualElement element) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("position", "0,0,0"); 
        map.put("billboard", "fixed");

        Vec3d offsetVec = element.getOffset();
        if (offsetVec.lengthSquared() > 0.000001) {
            map.put("translation", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", offsetVec.x, offsetVec.y, offsetVec.z));
        }

        if (element instanceof ItemDisplayElement itemEl) {
            writeItemStackData(map, itemEl.getItem());
            
            Vector3f scale = new Vector3f(itemEl.getScale());
            map.put("scale", String.format(Locale.ROOT, "%.3f,%.3f,%.3f", scale.x, scale.y, scale.z));
            
            Quaternionf rot = new Quaternionf(itemEl.getRightRotation());
            map.put("rotation", String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f", rot.x, rot.y, rot.z, rot.w));

            return map;
        } else if (element instanceof TextDisplayElement textEl) {
            map.put("text", textEl.getText().getString());
            map.put("billboard", "center");
            map.put("background_color", textEl.getBackground());
            return map;
        } else if (element instanceof BlockDisplayElement blockEl) {
            // [Enhanced] Block Display 支持
            map.put("block_state", Registries.BLOCK.getId(blockEl.getBlockState().getBlock()).toString());
            return map;
        } else if (element instanceof InteractionElement interactionEl) {
            // [Enhanced] Interaction 支持 (转换为 Hitbox 或虚拟配置)
            map.put("type", "interaction");
            map.put("width", interactionEl.getWidth());
            map.put("height", interactionEl.getHeight());
            return map;
        }
        
        return null;
    }

    private static void writeItemStackData(Map<String, Object> map, ItemStack stack) {
        map.put("item", Registries.ITEM.getId(stack.getItem()).toString());
        
        if (stack.contains(DataComponentTypes.CUSTOM_MODEL_DATA)) {
            CustomModelDataComponent cmd = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
            if (cmd != null && !cmd.floats().isEmpty()) {
                map.put("custom_model_data", (int) cmd.floats().get(0).floatValue());
            }
        }
        
        if (stack.contains(DataComponentTypes.DYED_COLOR)) {
            DyedColorComponent dyed = stack.get(DataComponentTypes.DYED_COLOR);
            if (dyed != null) {
                map.put("dyed_color", String.format("#%06X", (0xFFFFFF & dyed.rgb())));
            }
        }
    }

    // --- 工具方法 ---

    private static ServerPlayerEntity createSafeFakePlayer() {
        try {
            if (!(FakeWorld.INSTANCE_UNSAFE instanceof ServerWorld)) {
                LOGGER.warn("FakeWorld is not a ServerWorld instance, entity conversion might fail.");
                return null;
            }
            return new ServerPlayerEntity(
                (MinecraftServer) null,
                (ServerWorld) FakeWorld.INSTANCE_UNSAFE,
                new GameProfile(UUID.randomUUID(), "PolymerConverter"),
                SyncedClientOptions.createDefault()
            ) {
                @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
            };
        } catch (Exception e) {
            LOGGER.error("Failed to create fake player: ", e);
            return null;
        }
    }

    private static List<Pair<EquipmentSlot, ItemStack>> getAllEquipment(Entity entity) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                list.add(new Pair<>(slot, living.getEquippedStack(slot)));
            }
        }
        return list;
    }

    private static Map<String, Object> createElementFromEquipment(ItemStack stack, EquipmentSlot slot) {
        Map<String, Object> element = new LinkedHashMap<>();
        
        writeItemStackData(element, stack);
        
        element.put("display-transform", getDisplayContextForSlot(slot));
        element.put("position", "0,0,0");
        element.put("billboard", "fixed");
        
        applySlotTransform(element, slot);

        return element;
    }

    private static String getDisplayContextForSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case MAINHAND -> "third_person_right_hand";
            case OFFHAND -> "third_person_left_hand";
            case FEET, LEGS, CHEST, BODY -> "none";
            default -> "none"; 
        };
    }

    private static void applySlotTransform(Map<String, Object> element, EquipmentSlot slot) {
        switch (slot) {
            case HEAD:
                element.put("translation", "0.0,1.5,0.0");
                break;
            case MAINHAND:
                element.put("translation", "0.4,0.8,0.0");
                element.put("rotation", "0.0,0.0,-0.383,0.924"); 
                break;
            case OFFHAND:
                element.put("translation", "-0.4,0.8,0.0");
                element.put("rotation", "0.0,0.0,0.383,0.924");
                break;
            default:
                break;
        }
    }

    private static Map<String, Object> createMainHitbox(Entity entity) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "interaction");
        box.put("width", entity.getWidth());
        box.put("height", entity.getHeight());
        box.put("position", "0,0,0"); 
        box.put("invisible", true);
        
        if (entity instanceof LivingEntity) {
            box.put("interactive", true);
            box.put("can-be-hit-by-projectile", true);
        } else {
            box.put("blocks-building", true); 
        }
        
        return box;
    }

    private static boolean isDisplayEntity(EntityType<?> type) {
        return type == EntityType.ITEM_DISPLAY || 
               type == EntityType.BLOCK_DISPLAY || 
               type == EntityType.TEXT_DISPLAY;
    }
}