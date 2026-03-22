package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.packettweaker.PacketContext;

import com.mojang.authlib.GameProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class EntityConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerEntityConverter");

    private EntityConverterLogic() {
    }

    public static Map<String, Object> convert(EntityType<?> entityType, ServerLevel serverLevel) {
        Map<String, Object> furnitureConfig = new LinkedHashMap<>();

        Entity entity = createSafeEntity(entityType);
        if (entity == null) {
            return furnitureConfig;
        }

        PolymerEntity polymerEntity = PolymerEntity.get(entity);
        if (polymerEntity == null) {
            return furnitureConfig;
        }

        ServerPlayer fakePlayer = createSafeFakePlayer(serverLevel);
        PacketContext ctx = fakePlayer != null ? PacketContext.create(fakePlayer) : PacketContext.create();

        EntityType<?> visualType;
        try {
            visualType = polymerEntity.getPolymerEntityType(ctx);
        } catch (Throwable t) {
            LOGGER.debug("Failed to resolve polymer entity type for {}", BuiltInRegistries.ENTITY_TYPE.getKey(entityType), t);
            visualType = EntityType.ITEM_DISPLAY;
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("hit_times", 3);
        furnitureConfig.put("settings", settings);

        Map<String, Object> variants = new LinkedHashMap<>();
        Map<String, Object> defaultVariant = new LinkedHashMap<>();

        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(buildElementForVisualType(visualType));

        List<Map<String, Object>> hitboxes = new ArrayList<>();
        Map<String, Object> hitbox = new LinkedHashMap<>();
        hitbox.put("type", "interaction");
        hitbox.put("position", "0,0,0");
        hitbox.put("width", Math.max(0.1f, entity.getBbWidth()));
        hitbox.put("height", Math.max(0.1f, entity.getBbHeight()));
        hitbox.put("interactive", true);
        hitbox.put("invisible", true);
        hitbox.put("blocks_building", true);
        hitboxes.add(hitbox);

        defaultVariant.put("elements", elements);
        defaultVariant.put("hitboxes", hitboxes);
        variants.put("default", defaultVariant);
        furnitureConfig.put("variants", variants);

        return furnitureConfig;
    }

    private static Map<String, Object> buildElementForVisualType(EntityType<?> visualType) {
        Map<String, Object> element = new LinkedHashMap<>();
        element.put("position", "0,0,0");

        if (visualType == EntityType.BLOCK_DISPLAY) {
            element.put("block_state", "minecraft:stone");
        } else if (visualType == EntityType.TEXT_DISPLAY) {
            element.put("text", "Polymer Entity");
            element.put("billboard", "center");
        } else {
            element.put("item", "minecraft:barrier");
            element.put("display_transform", "none");
            element.put("billboard", "fixed");
        }

        return element;
    }

    private static Entity createSafeEntity(EntityType<?> type) {
        try {
            Entity entity = type.create(FakeWorld.INSTANCE_UNSAFE, EntitySpawnReason.LOAD);
            if (entity != null) {
                return entity;
            }
            return type.create(FakeWorld.INSTANCE_REGULAR, EntitySpawnReason.LOAD);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerPlayer createSafeFakePlayer(ServerLevel world) {
        if (world == null) {
            return null;
        }
        try {
            MinecraftServer server = world.getServer();
            return new ServerPlayer(server, world, new GameProfile(UUID.randomUUID(), "poly2ce"), ClientInformation.createDefault()) {
                @Override
                public boolean isSpectator() {
                    return false;
                }

                @Override
                public boolean isCreative() {
                    return false;
                }
            };
        } catch (Throwable t) {
            return null;
        }
    }
}
