package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerUtils;
import xyz.nucleoid.packettweaker.PacketContext;

// Mojang 映射导入
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.component.*;
import net.minecraft.world.item.*;
import net.minecraft.world.food.FoodProperties;

import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;

import java.util.*;

@SuppressWarnings({"null", "resource"})
public class ConverterLogic {
    
    // [修复] 修改方法签名：接收两个参数
    // registeredItem: 实际注册的物品 (用于创建 ItemStack, 获取注册ID)
    // polymerItem: Polymer 逻辑接口 (用于获取客户端模型, Overlay 逻辑)
    public static Map<String, Object> convert(Item registeredItem, PolymerItem polymerItem) {
        Map<String, Object> itemConfig = new LinkedHashMap<>();
        
        // [修复] 移除原来的 instanceof 检查，因为在 Overlay 模式下 polymerItem 可能不是 Item
        if (registeredItem == null || polymerItem == null) {
             itemConfig.put("_error", "Input item or logic cannot be null");
             return itemConfig;
        }

        ServerPlayer fakePlayer = null;
        PacketContext ctx = null;
        HolderLookup.Provider registryLookup = null;

        try {
            // [修复] 使用 registeredItem 创建 ItemStack
            // 这确保了我们操作的是真实存在的物品，而不是 Polymer 的代理对象
            ItemStack serverStack = new ItemStack(registeredItem);
            
            fakePlayer = createSafeFakePlayer();
            
            if (fakePlayer != null) {
                ctx = PacketContext.create(fakePlayer);
                registryLookup = fakePlayer.registryAccess();
            } else {
                ctx = PacketContext.create();
            }
            
            if (ctx == null) {
                itemConfig.put("_error", "Failed to create PacketContext");
                itemConfig.put("material", "barrier");
                return itemConfig;
            }

            // --- A. 基础材质与模型 ---
            // 使用 polymerItem 接口调用逻辑
            Item clientBaseItem = polymerItem.getPolymerItem(serverStack, ctx);
            if (clientBaseItem == null) {
                itemConfig.put("_error", "getPolymerItem returned null");
                itemConfig.put("material", "barrier");
                return itemConfig;
            }
            
            itemConfig.put("material", BuiltInRegistries.ITEM.getKey(clientBaseItem).toString());

            // 获取模型ID
            Identifier modelId = polymerItem.getPolymerItemModel(serverStack, ctx);
            if (modelId != null) {
                Map<String, Object> modelData = new LinkedHashMap<>();
                modelData.put("type", "minecraft:model");
                modelData.put("path", modelId.toString());
                itemConfig.put("model", modelData);
            }

            // --- B. 获取客户端堆栈 ---
            TooltipFlag tooltipType = fakePlayer != null ? 
                PolymerUtils.getTooltipType(fakePlayer) : TooltipFlag.Default.NORMAL;
            
            ItemStack clientStack = PolymerItemUtils.getPolymerItemStack(serverStack, tooltipType, ctx);
            
            if (clientStack == null || clientStack.isEmpty()) {
                itemConfig.put("_error", "getPolymerItemStack returned null or empty stack");
                return itemConfig;
            }

            Map<String, Object> dataMap = new LinkedHashMap<>();
            Map<String, Object> components = new LinkedHashMap<>();

            // --- 1. 显示名称 ---
            if (clientStack.has(DataComponents.CUSTOM_NAME)) {
                Component nameText = clientStack.getHoverName();
                if (nameText != null) {
                    dataMap.put("item-name", serializeText(nameText, registryLookup));
                }
            }

            // --- 2. 描述 (Lore) ---
            if (clientStack.has(DataComponents.LORE)) {
                ItemLore lore = clientStack.get(DataComponents.LORE);
                if (lore != null && !lore.lines().isEmpty()) {
                    List<String> loreLines = new ArrayList<>();
                    for (Component line : lore.lines()) {
                        loreLines.add(serializeText(line, registryLookup));
                    }
                    dataMap.put("lore", loreLines);
                }
            }

            // --- 3. CustomModelData ---
            if (clientStack.has(DataComponents.CUSTOM_MODEL_DATA)) {
                CustomModelData cmd = clientStack.get(DataComponents.CUSTOM_MODEL_DATA);
                if (cmd != null) {
                    if (!cmd.floats().isEmpty()) {
                        itemConfig.put("custom-model-data", (int) cmd.floats().getFirst().floatValue());
                    }
                    if (!cmd.strings().isEmpty()) {
                        itemConfig.put("custom-model-data-strings", cmd.strings());
                    }
                }
            }

            // --- 4. Item Model ---
            if (!itemConfig.containsKey("model") && clientStack.has(DataComponents.ITEM_MODEL)) {
                Identifier itemModelId = clientStack.get(DataComponents.ITEM_MODEL);
                if (itemModelId != null) {
                    Map<String, Object> modelData = new LinkedHashMap<>();
                    modelData.put("type", "minecraft:model");
                    modelData.put("path", itemModelId.toString());
                    itemConfig.put("model", modelData);
                }
            }

            // --- 5. 附魔 ---
            if (clientStack.has(DataComponents.ENCHANTMENTS)) {
                var enchants = clientStack.get(DataComponents.ENCHANTMENTS);
                if (enchants != null && !enchants.isEmpty()) {
                    Map<String, Integer> enchConfig = new LinkedHashMap<>();
                    enchants.entrySet().forEach(entry -> {
                        entry.getKey().unwrapKey().ifPresent(key -> {
                            String enchId = key.identifier().toString();
                            int level = entry.getIntValue();
                            enchConfig.put(enchId, level);
                        });
                    });
                    if (!enchConfig.isEmpty()) {
                        dataMap.put("enchantments", enchConfig);
                    }
                }
            }

            // --- 6. 属性修饰符 ---
            if (clientStack.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
                ItemAttributeModifiers attrs = clientStack.get(DataComponents.ATTRIBUTE_MODIFIERS);
                if (attrs != null && !attrs.modifiers().isEmpty()) {
                    List<Map<String, Object>> attrList = new ArrayList<>();
                    attrs.modifiers().forEach(entry -> {
                        Map<String, Object> attrMap = new LinkedHashMap<>();
                        entry.attribute().unwrapKey().ifPresent(key -> {
                            attrMap.put("type", key.identifier().toString());
                        });
                        attrMap.put("slot", entry.slot().getSerializedName());
                        attrMap.put("amount", entry.modifier().amount());
                        attrMap.put("operation", entry.modifier().operation().name().toLowerCase());
                        attrMap.put("id", entry.modifier().id().toString());
                        attrList.add(attrMap);
                    });
                    Map<String, Object> attrComp = new LinkedHashMap<>();
                    attrComp.put("modifiers", attrList);
                    components.put("minecraft:attribute_modifiers", attrComp);
                }
            }

            // --- 7. 染色 ---
            if (clientStack.has(DataComponents.DYED_COLOR)) {
                DyedItemColor dyedColor = clientStack.get(DataComponents.DYED_COLOR);
                if (dyedColor != null) {
                    int rgb = dyedColor.rgb();
                    String hex = String.format(Locale.ROOT, "#%06X", (0xFFFFFF & rgb));
                    dataMap.put("dyed-color", hex);
                }
            }

            // --- 8. 杂项属性 ---
            if (clientStack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) {
                Boolean glint = clientStack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
                if (glint != null) {
                    dataMap.put("enchantment-glint-override", glint);
                }
            }

            if (clientStack.has(DataComponents.UNBREAKABLE)) {
                dataMap.put("unbreakable", true);
            }

            if (clientStack.has(DataComponents.MAX_DAMAGE)) {
                Integer maxDamage = clientStack.get(DataComponents.MAX_DAMAGE);
                if (maxDamage != null) {
                    dataMap.put("max-damage", maxDamage);
                }
            }

            if (clientStack.has(DataComponents.DAMAGE)) {
                Integer damage = clientStack.get(DataComponents.DAMAGE);
                if (damage != null) {
                    dataMap.put("damage", damage);
                }
            }

            if (clientStack.has(DataComponents.REPAIR_COST)) {
                Integer repairCost = clientStack.get(DataComponents.REPAIR_COST);
                if (repairCost != null) {
                    dataMap.put("repair-cost", repairCost);
                }
            }

            // --- 9. 食物 ---
            if (clientStack.has(DataComponents.FOOD)) {
                FoodProperties food = clientStack.get(DataComponents.FOOD);
                if (food != null) {
                    Map<String, Object> foodMap = new LinkedHashMap<>();
                    foodMap.put("nutrition", food.nutrition());
                    foodMap.put("saturation", food.saturation());
                    foodMap.put("can_always_eat", food.canAlwaysEat());
                    components.put("minecraft:food", foodMap);
                }
            }

            // --- 10. 消耗品 (1.21.2+) ---
            try {
                if (clientStack.has(DataComponents.CONSUMABLE)) {
                    Consumable consumable = clientStack.get(DataComponents.CONSUMABLE);
                    if (consumable != null) {
                        Map<String, Object> consumeMap = new LinkedHashMap<>();
                        consumeMap.put("consume_seconds", consumable.consumeSeconds());
                        if (consumable.animation() != null) {
                            consumeMap.put("animation", consumable.animation().name().toLowerCase());
                        }
                        components.put("minecraft:consumable", consumeMap);
                    }
                }
            } catch (Throwable ignored) {}

            // --- 11. 冷却 ---
            try {
                if (clientStack.has(DataComponents.USE_COOLDOWN)) {
                    UseCooldown cooldown = clientStack.get(DataComponents.USE_COOLDOWN);
                    if (cooldown != null) {
                        Map<String, Object> cdMap = new LinkedHashMap<>();
                        cdMap.put("seconds", cooldown.seconds());
                        cooldown.cooldownGroup().ifPresent(group -> 
                            cdMap.put("group", group.toString())
                        );
                        components.put("minecraft:use_cooldown", cdMap);
                    }
                }
            } catch (Throwable ignored) {}

            // --- 12. 工具 ---
            if (clientStack.has(DataComponents.TOOL)) {
                Tool tool = clientStack.get(DataComponents.TOOL);
                if (tool != null) {
                    Map<String, Object> toolMap = new LinkedHashMap<>();
                    toolMap.put("default_mining_speed", tool.defaultMiningSpeed());
                    toolMap.put("damage_per_block", tool.damagePerBlock());
                    components.put("minecraft:tool", toolMap);
                }
            }

            // --- 13. 稀有度 ---
            if (clientStack.has(DataComponents.RARITY)) {
                Rarity rarity = clientStack.get(DataComponents.RARITY);
                if (rarity != null) {
                    dataMap.put("rarity", rarity.name().toLowerCase());
                }
            }

            // --- 14. 盔甲纹饰 ---
            try {
                if (clientStack.has(DataComponents.TRIM)) {
                    var trim = clientStack.get(DataComponents.TRIM);
                    if (trim != null) {
                        Map<String, Object> trimMap = new LinkedHashMap<>();
                        trim.material().unwrapKey().ifPresent(key -> 
                            trimMap.put("material", key.identifier().toString())
                        );
                        trim.pattern().unwrapKey().ifPresent(key -> 
                            trimMap.put("pattern", key.identifier().toString())
                        );
                        components.put("minecraft:trim", trimMap);
                    }
                }
            } catch (Throwable ignored) {}

            // --- 15. 可装备 ---
            try {
                if (clientStack.has(DataComponents.EQUIPPABLE)) {
                    var equippable = clientStack.get(DataComponents.EQUIPPABLE);
                    if (equippable != null) {
                        Map<String, Object> eqMap = new LinkedHashMap<>();
                        eqMap.put("slot", equippable.slot().getSerializedName());
                        equippable.assetId().ifPresent(id -> 
                            eqMap.put("model", id.toString())
                        );
                        components.put("minecraft:equippable", eqMap);
                    }
                }
            } catch (Throwable ignored) {}

            // --- 16. 唱片机 ---
            try {
                if (clientStack.has(DataComponents.JUKEBOX_PLAYABLE)) {
                    JukeboxPlayable jukebox = clientStack.get(DataComponents.JUKEBOX_PLAYABLE);
                    if (jukebox != null) {
                        Map<String, Object> jbMap = new LinkedHashMap<>();
                        // 使用 key() 而不是 unwrapKey()
                        var songHolder = jukebox.song();
                        if (songHolder.key().isPresent()) {
                            jbMap.put("song", songHolder.key().get().identifier().toString());
                        }
                        components.put("minecraft:jukebox_playable", jbMap);
                    }
                }
            } catch (Throwable ignored) {}

            // --- 17. 烟花 ---
            if (clientStack.has(DataComponents.FIREWORKS)) {
                Fireworks fireworks = clientStack.get(DataComponents.FIREWORKS);
                if (fireworks != null) {
                    dataMap.put("flight", fireworks.flightDuration());
                }
            }

            // 组装最终数据
            if (!components.isEmpty()) {
                dataMap.put("components", components);
            }

            if (!dataMap.isEmpty()) {
                itemConfig.put("data", dataMap);
            }

        } catch (Exception e) {
            itemConfig.put("_error", "Conversion error: " + e.getMessage());
            itemConfig.put("_error_type", e.getClass().getSimpleName());
            itemConfig.put("_stack_trace", getStackTraceString(e));
            itemConfig.put("material", "barrier");
        }

        return itemConfig;
    }

    private static String serializeText(Component text, HolderLookup.Provider registries) {
        if (text == null) {
            return "";
        }
        
        if (registries != null) {
            try {
                return ComponentSerialization.CODEC
                    .encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), text)
                    .result()
                    .map(JsonElement::toString)
                    .orElseGet(() -> "<!i>" + text.getString());
            } catch (Exception ignored) {}
        }
        
        return "<!i>" + text.getString();
    }

    private static ServerPlayer createSafeFakePlayer() {
        try {
            ServerLevel world = null;
            if (FakeWorld.INSTANCE_UNSAFE instanceof ServerLevel serverLevel) {
                world = serverLevel;
            } else if (FakeWorld.INSTANCE_REGULAR instanceof ServerLevel serverLevel) {
                world = serverLevel;
            }

            if (world == null) return null;

            return new ServerPlayer(
                world.getServer(),
                world,
                new GameProfile(UUID.randomUUID(), "PolymerItemConverter"),
                ClientInformation.createDefault()
            ) {
                @Override
                public boolean isSpectator() { return false; }
                @Override
                public boolean isCreative() { return false; }
            };
        } catch (Exception e) {
            System.err.println("Failed to create fake player: " + e.getMessage());
            return null;
        }
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