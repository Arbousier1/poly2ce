package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.common.impl.entity.InternalEntityHelpers;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import xyz.nucleoid.packettweaker.PacketContext;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.*;

// 基础导入
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;

import net.minecraft.resources.Identifier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.phys.Vec3;

import com.mojang.datafixers.util.Pair;
import com.mojang.authlib.GameProfile;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;

@SuppressWarnings({"null", "resource"})
public class EntityConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerEntityConverter");

    public static Map<String, Object> convert(EntityType<?> entityType) {
        Map<String, Object> furnitureConfig = new LinkedHashMap<>();

        try {
            // Check if registered with Polymer (this check is fine)
            if (!PolymerEntityUtils.isPolymerEntityType(entityType)) {
                return furnitureConfig;
            }

            Entity entity = createSafeEntity(entityType);
            if (entity == null) {
                furnitureConfig.put("_error", "Failed to create template entity for " +
                        BuiltInRegistries.ENTITY_TYPE.getKey(entityType));
                return furnitureConfig;
            }

            // [修复] 使用 PolymerEntity.get(entity) 获取实例
            // PolymerEntityUtils 没有 getPolymerEntity 方法，正确的方法在 PolymerEntity 接口上
            PolymerEntity polymerEntity = PolymerEntity.get(entity);
            
            if (polymerEntity == null) {
                furnitureConfig.put("_error", "Entity is registered but no PolymerEntity logic found: " +
                        BuiltInRegistries.ENTITY_TYPE.getKey(entityType));
                return furnitureConfig;
            }

            ServerPlayer fakePlayer = createSafeFakePlayer();
            if (fakePlayer == null) {
                furnitureConfig.put("_error", "Failed to create fake player context");
                return furnitureConfig;
            }

            PacketContext ctx = PacketContext.create(fakePlayer);

            List<Map<String, Object>> elements = new ArrayList<>();
            List<Map<String, Object>> hitboxes = new ArrayList<>();

            LOGGER.info("Converting Polymer entity: {}", BuiltInRegistries.ENTITY_TYPE.getKey(entityType));

            // 1. Process Equipment
            List<Pair<EquipmentSlot, ItemStack>> allEquipment = getAllEquipment(entity);
            List<Pair<EquipmentSlot, ItemStack>> visibleEquipment =
                    polymerEntity.getPolymerVisibleEquipment(allEquipment, fakePlayer);

            for (Pair<EquipmentSlot, ItemStack> pair : visibleEquipment) {
                if (!pair.getSecond().isEmpty()) {
                    elements.add(createElementFromEquipment(pair.getSecond(), pair.getFirst()));
                }
            }

            // 2. Process Visual Representation
            EntityType<?> visualType = polymerEntity.getPolymerEntityType(ctx);

            List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes = new ArrayList<>();
            try {
                polymerEntity.modifyRawEntityAttributeData(attributes, fakePlayer, true);
            } catch (Exception e) {
                LOGGER.debug("Failed to get entity attributes: {}", e.getMessage());
            }

            double entityScale = extractScaleFromAttributes(attributes);

            if (isDisplayEntity(visualType)) {
                Map<String, Object> displayElement = new LinkedHashMap<>();
                displayElement.put("position", "0,0,0");

                if (visualType == EntityType.TEXT_DISPLAY) {
                    displayElement.put("billboard", "center");
                } else {
                    displayElement.put("billboard", "fixed");
                }

                if (Math.abs(entityScale - 1.0) > 0.001) {
                    displayElement.put("scale", String.format(Locale.ROOT,
                            "%.3f,%.3f,%.3f", entityScale, entityScale, entityScale));
                }

                List<SynchedEntityData.DataValue<?>> trackedData = new ArrayList<>();
                try {
                    polymerEntity.modifyRawTrackedData(trackedData, fakePlayer, true);
                } catch (Exception e) {
                    LOGGER.debug("Failed to get tracked data: {}", e.getMessage());
                }

                applyTrackedDataToElement(displayElement, trackedData, visualType);

                if (visualType == EntityType.ITEM_DISPLAY && !displayElement.containsKey("item")) {
                    displayElement.put("item", "minecraft:barrier");
                } else if (visualType == EntityType.TEXT_DISPLAY && !displayElement.containsKey("text")) {
                    displayElement.put("text", "Text Display");
                }

                elements.add(displayElement);
            }

            // 3. Process VirtualElement
            extractElementHolderComponents(entity, elements);

            // 4. Generate Hitbox
            hitboxes.add(createMainHitbox(entity));

            if (elements.isEmpty()) {
                LOGGER.warn("No elements extracted for entity {}, adding fallback",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entityType));
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
            LOGGER.error("Entity conversion failed for {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(entityType), e);
            furnitureConfig.put("_error", "Conversion error: " + e.getMessage());
            furnitureConfig.put("_error_type", e.getClass().getSimpleName());
            furnitureConfig.put("_stack_trace", getStackTraceString(e));
        }

        return furnitureConfig;
    }

    // --- Core Conversion Logic ---

    private static double extractScaleFromAttributes(List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes) {
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot entry : attributes) {
            try {
                var attribute = entry.attribute().value();
                Identifier attributeId = BuiltInRegistries.ATTRIBUTE.getKey(attribute);

                if (attributeId != null && attributeId.getPath().contains("scale")) {
                    return entry.base();
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to extract scale attribute: {}", e.getMessage());
            }
        }
        return 1.0;
    }

    private static void applyTrackedDataToElement(Map<String, Object> element,
                                                  List<SynchedEntityData.DataValue<?>> entries,
                                                  EntityType<?> type) {
        for (SynchedEntityData.DataValue<?> entry : entries) {
            Object value = entry.value();

            if (value instanceof Vector3f vec) {
                if (Math.abs(vec.x - vec.y) < 0.001 &&
                        Math.abs(vec.y - vec.z) < 0.001 &&
                        Math.abs(vec.x) > 0.01) {
                    element.put("scale", String.format(Locale.ROOT,
                            "%.3f,%.3f,%.3f", vec.x, vec.y, vec.z));
                } else {
                    element.put("translation", String.format(Locale.ROOT,
                            "%.3f,%.3f,%.3f", vec.x, vec.y, vec.z));
                }
            } else if (value instanceof Quaternionf quat) {
                element.put("rotation", String.format(Locale.ROOT,
                        "%.3f,%.3f,%.3f,%.3f", quat.x, quat.y, quat.z, quat.w));
            } else if (value instanceof ItemStack stack && type == EntityType.ITEM_DISPLAY) {
                writeItemStackData(element, stack);
            } else if (value instanceof Component text && type == EntityType.TEXT_DISPLAY) {
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
        } catch (Throwable e) {
            LOGGER.debug("Failed to extract ElementHolder components: {}", e.getMessage());
        }
    }

    private static Map<String, Object> convertVirtualElement(VirtualElement element) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("position", "0,0,0");
        map.put("billboard", "fixed");

        Vec3 offsetVec = element.getOffset();
        if (offsetVec.lengthSqr() > 0.000001) {
            map.put("translation", String.format(Locale.ROOT,
                    "%.3f,%.3f,%.3f", offsetVec.x, offsetVec.y, offsetVec.z));
        }

        if (element instanceof ItemDisplayElement itemEl) {
            writeItemStackData(map, itemEl.getItem());

            Vector3f scale = new Vector3f(itemEl.getScale());
            map.put("scale", String.format(Locale.ROOT,
                    "%.3f,%.3f,%.3f", scale.x, scale.y, scale.z));

            Quaternionf rot = new Quaternionf(itemEl.getRightRotation());
            map.put("rotation", String.format(Locale.ROOT,
                    "%.3f,%.3f,%.3f,%.3f", rot.x, rot.y, rot.z, rot.w));

            return map;
        } else if (element instanceof TextDisplayElement textEl) {
            map.put("text", textEl.getText().getString());
            map.put("billboard", "center");
            map.put("background_color", textEl.getBackground());
            return map;
        } else if (element instanceof BlockDisplayElement blockEl) {
            map.put("block_state",
                    BuiltInRegistries.BLOCK.getKey(blockEl.getBlockState().getBlock()).toString());
            return map;
        } else if (element instanceof InteractionElement interactionEl) {
            map.put("type", "interaction");
            map.put("width", interactionEl.getWidth());
            map.put("height", interactionEl.getHeight());
            return map;
        }

        return null;
    }

    private static void writeItemStackData(Map<String, Object> map, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        map.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());

        if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) {
            CustomModelData cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
            if (cmd != null && !cmd.floats().isEmpty()) {
                map.put("custom_model_data", (int) cmd.floats().getFirst().floatValue());
            }
        }

        if (stack.has(DataComponents.DYED_COLOR)) {
            DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
            if (dyed != null) {
                map.put("dyed_color", String.format("#%06X", (0xFFFFFF & dyed.rgb())));
            }
        }
    }

    // --- Utility Methods ---

    private static Entity createSafeEntity(EntityType<?> entityType) {
        try {
            Entity entity = InternalEntityHelpers.getEntity(entityType);

            if (entity != null && !entity.getClass().getName().contains("FakeEntity")) {
                return entity;
            }

            entity = entityType.create(FakeWorld.INSTANCE_UNSAFE, EntitySpawnReason.LOAD);
            if (entity == null) {
                entity = entityType.create(FakeWorld.INSTANCE_REGULAR, EntitySpawnReason.LOAD);
            }

            return entity;
        } catch (Exception e) {
            LOGGER.error("Failed to create entity: {}",
                    BuiltInRegistries.ENTITY_TYPE.getKey(entityType), e);
            return null;
        }
    }

    private static ServerPlayer createSafeFakePlayer() {
        try {
            ServerLevel world = null;
            MinecraftServer server = null;

            if (FakeWorld.INSTANCE_UNSAFE instanceof ServerLevel serverLevel) {
                world = serverLevel;
                server = world.getServer();
            } else if (FakeWorld.INSTANCE_REGULAR instanceof ServerLevel serverLevel) {
                world = serverLevel;
                server = world.getServer();
            }

            if (world == null) {
                LOGGER.error("Cannot find valid ServerLevel instance for fake player");
                return null;
            }

            final ServerLevel finalWorld = world;
            final MinecraftServer finalServer = server;

            return new ServerPlayer(
                    finalServer,
                    finalWorld,
                    new GameProfile(UUID.randomUUID(), "PolymerConverter"),
                    net.minecraft.server.level.ClientInformation.createDefault()
            ) {
                @Override
                public boolean isSpectator() {
                    return false;
                }

                @Override
                public boolean isCreative() {
                    return false;
                }
            };
        } catch (Exception e) {
            LOGGER.error("Failed to create fake player context", e);
            return null;
        }
    }

    private static List<Pair<EquipmentSlot, ItemStack>> getAllEquipment(Entity entity) {
        List<Pair<EquipmentSlot, ItemStack>> list = new ArrayList<>();
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    list.add(new Pair<>(slot, stack));
                }
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
            default -> "none";
        };
    }

    private static void applySlotTransform(Map<String, Object> element, EquipmentSlot slot) {
        switch (slot) {
            case HEAD -> element.put("translation", "0.0,1.5,0.0");
            case MAINHAND -> {
                element.put("translation", "0.4,0.8,0.0");
                element.put("rotation", "0.0,0.0,-0.383,0.924");
            }
            case OFFHAND -> {
                element.put("translation", "-0.4,0.8,0.0");
                element.put("rotation", "0.0,0.0,0.383,0.924");
            }
            default -> {
            }
        }
    }

    private static Map<String, Object> createMainHitbox(Entity entity) {
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("type", "interaction");
        box.put("width", entity.getBbWidth());
        box.put("height", entity.getBbHeight());
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