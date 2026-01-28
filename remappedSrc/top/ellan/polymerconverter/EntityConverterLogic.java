package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.common.impl.entity.InternalEntityHelpers;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import xyz.nucleoid.packettweaker.PacketContext;
// 如果环境没有 polymer-virtual-entity，请注释掉下方 import
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.Element;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Pair;
import net.minecraft.world.GameMode;
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

                // --- A. 提取装备 (Visible Equipment) ---
                List<Pair<EquipmentSlot, ItemStack>> allEquipment = getAllEquipment(entity);
                List<Pair<EquipmentSlot, ItemStack>> visibleEquipment = 
                    polymerEntity.getPolymerVisibleEquipment(allEquipment, fakePlayer);

                for (Pair<EquipmentSlot, ItemStack> pair : visibleEquipment) {
                    if (!pair.getRight().isEmpty()) {
                        elements.add(createElementFromEquipment(pair.getRight(), pair.getLeft()));
                    }
                }

                // --- B. 提取 Display Entity 数据 ---
                EntityType<?> visualType = polymerEntity.getPolymerEntityType(ctx);
                if (isDisplayEntity(visualType)) {
                    Map<String, Object> displayElement = new LinkedHashMap<>();
                    displayElement.put("position", "0,0,0"); // Base position
                    
                    // 默认 Billboard
                    if (visualType == EntityType.TEXT_DISPLAY) {
                        displayElement.put("billboard", "center"); // 文字通常面向玩家
                    } else {
                        displayElement.put("billboard", "fixed");
                    }

                    // 提取 Tracker 数据
                    List<DataTracker.SerializedEntry<?>> trackedData = new ArrayList<>();
                    try {
                        polymerEntity.modifyRawTrackedData(trackedData, fakePlayer, true);
                        LOGGER.debug("Extracted {} tracked data entries", trackedData.size());
                    } catch (Exception ignored) {}

                    applyTrackedDataToElement(displayElement, trackedData, visualType);
                    
                    // 验证必要字段
                    if (visualType == EntityType.ITEM_DISPLAY && !displayElement.containsKey("item")) {
                        displayElement.put("item", "minecraft:barrier"); 
                    } else if (visualType == EntityType.TEXT_DISPLAY && !displayElement.containsKey("text")) {
                        displayElement.put("text", "Text Display");
                    }
                    
                    elements.add(displayElement);
                }

                // --- C. 提取 ElementHolder ---
                extractElementHolderComponents(entity, elements);

                // --- D. 生成碰撞箱 ---
                hitboxes.add(createMainHitbox(entity));
            }

            // --- 兜底处理 ---
            if (elements.isEmpty()) {
                Map<String, Object> fallback = new LinkedHashMap<>();
                fallback.put("item", "minecraft:barrier"); 
                fallback.put("position", "0,0,0");
                fallback.put("billboard", "fixed");
                fallback.put("scale", "0.5,0.5,0.5");
                elements.add(fallback);
            }

            // --- 组装配置 ---
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

    // ================= 核心逻辑方法 =================

    private static void applyTrackedDataToElement(Map<String, Object> element, List<DataTracker.SerializedEntry<?>> entries, EntityType<?> type) {
        for (DataTracker.SerializedEntry<?> entry : entries) {
            Object value = entry.value();
            // 简单的启发式类型判断
            if (value instanceof Vector3f vec) {
                // 通常 Scale 默认为 1,1,1 (非零)，Translation 默认为 0,0,0
                // 这是一个猜测，但大多数情况有效
                if (Math.abs(vec.x - vec.y) < 0.001 && Math.abs(vec.y - vec.z) < 0.001 && Math.abs(vec.x) > 0.01) {
                    element.put("scale", String.format("%.3f,%.3f,%.3f", vec.x, vec.y, vec.z));
                } else {
                    element.put("translation", String.format("%.3f,%.3f,%.3f", vec.x, vec.y, vec.z));
                }
            } else if (value instanceof Quaternionf quat) {
                element.put("rotation", String.format("%.3f,%.3f,%.3f,%.3f", quat.x, quat.y, quat.z, quat.w));
            } else if (value instanceof ItemStack stack && type == EntityType.ITEM_DISPLAY) {
                element.put("item", Registries.ITEM.getId(stack.getItem()).toString());
            } else if (value instanceof Text text && type == EntityType.TEXT_DISPLAY) {
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
                        for (Element virtualElement : holder.getElements()) {
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

    private static Map<String, Object> convertVirtualElement(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("position", "0,0,0"); 
        map.put("billboard", "fixed"); // 默认为固定

        Vector3f offset = element.getOffset().toVector3f();
        if (offset.length() > 0.001) {
            map.put("translation", String.format("%.3f,%.3f,%.3f", offset.x, offset.y, offset.z));
        }

        if (element instanceof ItemDisplayElement itemEl) {
            ItemStack stack = itemEl.getItem();
            map.put("item", Registries.ITEM.getId(stack.getItem()).toString());
            
            Vector3f scale = itemEl.getScale();
            if (scale != null) map.put("scale", String.format("%.3f,%.3f,%.3f", scale.x, scale.y, scale.z));
            
            // ItemDisplayElement 的旋转通常存储在 rightRotation
            Quaternionf rot = itemEl.getRightRotation();
            if (rot != null) map.put("rotation", String.format("%.3f,%.3f,%.3f,%.3f", rot.x, rot.y, rot.z, rot.w));

            return map;
        } else if (element instanceof TextDisplayElement textEl) {
            map.put("text", textEl.getText().getString());
            map.put("billboard", "center"); // 覆盖为面向玩家
            return map;
        }
        
        return null;
    }

    // ================= 辅助方法 =================

    private static ServerPlayerEntity createSafeFakePlayer() {
        try {
            return new ServerPlayerEntity(
                (MinecraftServer) null,
                FakeWorld.INSTANCE_UNSAFE,
                new GameProfile(UUID.randomUUID(), "PolymerConverter"),
                null
            ) {
                @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
            };
        } catch (Exception e) {
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
        
        element.put("item", Registries.ITEM.getId(stack.getItem()).toString());
        element.put("display-transform", getDisplayContextForSlot(slot)); // [FIX] Field Name
        element.put("position", "0,0,0"); // [FIX] Required
        element.put("billboard", "fixed"); // [FIX] Default
        
        applySlotTransform(element, slot);

        return element;
    }

    private static String getDisplayContextForSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "head";
            case MAINHAND -> "third_person_right_hand";
            case OFFHAND -> "third_person_left_hand";
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
                // 绕 Z 轴 -45 度
                element.put("rotation", "0.0,0.0,-0.383,0.924"); 
                break;
            case OFFHAND:
                element.put("translation", "-0.4,0.8,0.0");
                // 绕 Z 轴 45 度
                element.put("rotation", "0.0,0.0,0.383,0.924");
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
            // [FIX] 非生物实体默认为阻挡
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