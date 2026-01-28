package top.ellan.polymerconverter;

import eu.pb4.polymer.common.impl.FakeWorld;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import xyz.nucleoid.packettweaker.PacketContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.world.GameMode;
import com.mojang.authlib.GameProfile;

// [Fix] 新增导入：用于处理文本序列化
import net.minecraft.text.TextCodecs;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;

import java.util.*;

public class ConverterLogic {

    public static Map<String, Object> convert(PolymerItem polymerItem) {
        Map<String, Object> itemConfig = new LinkedHashMap<>();
        
        if (!(polymerItem instanceof Item)) {
            itemConfig.put("_error", "PolymerItem is not an instance of Item");
            return itemConfig;
        }

        // 1. 准备环境
        ItemStack serverStack = new ItemStack((Item) polymerItem);
        ServerPlayerEntity fakePlayer = createSafeFakePlayer();
        PacketContext ctx = fakePlayer != null ? PacketContext.create(fakePlayer) : PacketContext.create();
        // 获取注册表查找器供 Text 序列化使用
        RegistryWrapper.WrapperLookup registryLookup = fakePlayer != null ? fakePlayer.getRegistryManager() : null;

        try {
            // --- A. 基础材质与模型 ---
            Item clientBaseItem = polymerItem.getPolymerItem(serverStack, ctx);
            itemConfig.put("material", Registries.ITEM.getId(clientBaseItem).toString());

            Identifier modelId = polymerItem.getPolymerItemModel(serverStack, ctx);
            if (modelId != null) {
                Map<String, Object> modelData = new LinkedHashMap<>();
                modelData.put("type", "minecraft:model");
                modelData.put("path", modelId.toString());
                itemConfig.put("model", modelData);
            }

            // --- B. 获取客户端堆栈 ---
            ItemStack clientStack = PolymerItemUtils.getPolymerItemStack(serverStack, TooltipType.BASIC, ctx);
            
            Map<String, Object> dataMap = new LinkedHashMap<>();
            Map<String, Object> components = new LinkedHashMap<>();

            // --- 1. 显示名称 ---
            if (clientStack.contains(DataComponentTypes.CUSTOM_NAME)) {
                Text nameText = clientStack.getName();
                dataMap.put("item-name", serializeText(nameText, registryLookup)); 
            }

            // --- 2. 描述 (Lore) ---
            if (clientStack.contains(DataComponentTypes.LORE)) {
                LoreComponent lore = clientStack.get(DataComponentTypes.LORE);
                if (lore != null) {
                    List<String> loreLines = new ArrayList<>();
                    for (Text line : lore.lines()) {
                        loreLines.add(serializeText(line, registryLookup));
                    }
                    dataMap.put("lore", loreLines);
                }
            }

            // --- 3. CustomModelData ---
            if (clientStack.contains(DataComponentTypes.CUSTOM_MODEL_DATA)) {
                CustomModelDataComponent cmd = clientStack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
                if (cmd != null) {
                    if (!cmd.floats().isEmpty()) {
                        itemConfig.put("custom-model-data", (int) cmd.floats().get(0).floatValue());
                    }
                    if (!cmd.strings().isEmpty()) {
                        itemConfig.put("custom-model-data-strings", cmd.strings());
                    }
                }
            }
            
            // --- 4. Item Model ---
            if (!itemConfig.containsKey("model") && clientStack.contains(DataComponentTypes.ITEM_MODEL)) {
                Identifier itemModelId = clientStack.get(DataComponentTypes.ITEM_MODEL);
                if (itemModelId != null) {
                    Map<String, Object> modelData = new LinkedHashMap<>();
                    modelData.put("type", "minecraft:model");
                    modelData.put("path", itemModelId.toString());
                    itemConfig.put("model", modelData);
                }
            }

            // --- 5. 附魔 ---
            if (clientStack.contains(DataComponentTypes.ENCHANTMENTS)) {
                ItemEnchantmentsComponent enchants = clientStack.get(DataComponentTypes.ENCHANTMENTS);
                if (enchants != null && !enchants.isEmpty()) {
                    Map<String, Integer> enchConfig = new LinkedHashMap<>();
                    enchants.getEnchantmentEntries().forEach(entry -> {
                        String enchId = entry.getKey().getIdAsString();
                        int level = entry.getIntValue();
                        enchConfig.put(enchId, level);
                    });
                    dataMap.put("enchantments", enchConfig);
                }
            }

            // --- 6. 属性修饰符 ---
            if (clientStack.contains(DataComponentTypes.ATTRIBUTE_MODIFIERS)) {
                AttributeModifiersComponent attrs = clientStack.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
                if (attrs != null && !attrs.modifiers().isEmpty()) {
                    List<Map<String, Object>> attrList = new ArrayList<>();
                    attrs.modifiers().forEach(entry -> {
                        Map<String, Object> attrMap = new LinkedHashMap<>();
                        attrMap.put("type", entry.attribute().getIdAsString());
                        attrMap.put("slot", entry.slot().asString());
                        attrMap.put("amount", entry.modifier().value());
                        attrMap.put("operation", entry.modifier().operation().asString());
                        attrMap.put("id", entry.modifier().id().toString());
                        attrList.add(attrMap);
                    });
                    
                    Map<String, Object> attrComp = new LinkedHashMap<>();
                    attrComp.put("modifiers", attrList);
                    components.put("minecraft:attribute_modifiers", attrComp);
                }
            }

            // --- 7. 染色 ---
            if (clientStack.contains(DataComponentTypes.DYED_COLOR)) {
                DyedColorComponent dyedColor = clientStack.get(DataComponentTypes.DYED_COLOR);
                if (dyedColor != null) {
                    int rgb = dyedColor.rgb();
                    String hex = String.format("#%06X", (0xFFFFFF & rgb));
                    dataMap.put("dyed-color", hex);
                }
            }

            // --- 8. 杂项属性 ---
            if (clientStack.contains(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)) {
                Boolean glint = clientStack.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
                if (glint != null) dataMap.put("enchantment-glint-override", glint);
            }
            if (clientStack.contains(DataComponentTypes.UNBREAKABLE)) {
                dataMap.put("unbreakable", true);
            }
            if (clientStack.contains(DataComponentTypes.MAX_DAMAGE)) {
                dataMap.put("max-damage", clientStack.get(DataComponentTypes.MAX_DAMAGE));
            }
            if (clientStack.contains(DataComponentTypes.DAMAGE)) {
                 dataMap.put("damage", clientStack.get(DataComponentTypes.DAMAGE));
            }
            if (clientStack.contains(DataComponentTypes.REPAIR_COST)) {
                 dataMap.put("repair-cost", clientStack.get(DataComponentTypes.REPAIR_COST));
            }

            // --- 9. 食物 ---
            if (clientStack.contains(DataComponentTypes.FOOD)) {
                FoodComponent food = clientStack.get(DataComponentTypes.FOOD);
                if (food != null) {
                    Map<String, Object> foodMap = new LinkedHashMap<>();
                    foodMap.put("nutrition", food.nutrition());
                    foodMap.put("saturation", food.saturation());
                    foodMap.put("can_always_eat", food.canAlwaysEat());
                    components.put("minecraft:food", foodMap);
                }
            }
            
            // --- 10. 消耗品 ---
            if (clientStack.contains(DataComponentTypes.CONSUMABLE)) {
                ConsumableComponent consumable = clientStack.get(DataComponentTypes.CONSUMABLE);
                if (consumable != null) {
                    Map<String, Object> consumeMap = new LinkedHashMap<>();
                    consumeMap.put("consume_seconds", consumable.consumeSeconds());
                    if (consumable.useAction() != null) {
                        consumeMap.put("animation", consumable.useAction().name().toLowerCase());
                    }
                    components.put("minecraft:consumable", consumeMap);
                }
            }

            // --- 11. 冷却 ---
            if (clientStack.contains(DataComponentTypes.USE_COOLDOWN)) {
                UseCooldownComponent cooldown = clientStack.get(DataComponentTypes.USE_COOLDOWN);
                if (cooldown != null) {
                     Map<String, Object> cdMap = new LinkedHashMap<>();
                     cdMap.put("seconds", cooldown.seconds());
                     cooldown.cooldownGroup().ifPresent(group -> 
                         cdMap.put("group", group.toString())
                     );
                     components.put("minecraft:use_cooldown", cdMap);
                }
            }

            // --- 12. 工具 ---
            if (clientStack.contains(DataComponentTypes.TOOL)) {
                ToolComponent tool = clientStack.get(DataComponentTypes.TOOL);
                if (tool != null) {
                    Map<String, Object> toolMap = new LinkedHashMap<>();
                    toolMap.put("default_mining_speed", tool.defaultMiningSpeed());
                    toolMap.put("damage_per_block", tool.damagePerBlock());
                    components.put("minecraft:tool", toolMap);
                }
            }

            // --- 13. 稀有度 ---
            if (clientStack.contains(DataComponentTypes.RARITY)) {
                Rarity rarity = clientStack.get(DataComponentTypes.RARITY);
                if (rarity != null) {
                    dataMap.put("rarity", rarity.name().toLowerCase());
                }
            }

            if (clientStack.contains(DataComponentTypes.TOOLTIP_STYLE)) {
                Identifier style = clientStack.get(DataComponentTypes.TOOLTIP_STYLE);
                if (style != null) {
                    dataMap.put("tooltip-style", style.toString());
                }
            }

            // --- 14. 盔甲纹饰 (Trim) ---
            if (clientStack.contains(DataComponentTypes.TRIM)) {
                var trim = clientStack.get(DataComponentTypes.TRIM);
                if (trim != null) {
                    Map<String, Object> trimMap = new LinkedHashMap<>();
                    trim.material().getKey().ifPresent(key -> 
                        trimMap.put("material", key.getValue().toString())
                    );
                    trim.pattern().getKey().ifPresent(key -> 
                        trimMap.put("pattern", key.getValue().toString())
                    );
                    components.put("minecraft:trim", trimMap);
                }
            }

            // --- 15. 可装备 ---
            if (clientStack.contains(DataComponentTypes.EQUIPPABLE)) {
                EquippableComponent equippable = clientStack.get(DataComponentTypes.EQUIPPABLE);
                if (equippable != null) {
                    Map<String, Object> eqMap = new LinkedHashMap<>();
                    eqMap.put("slot", equippable.slot().asString());
                    equippable.assetId().ifPresent(id -> 
                        eqMap.put("model", id.toString())
                    );
                    components.put("minecraft:equippable", eqMap);
                }
            }
            
            // --- 16. 唱片机 ---
            if (clientStack.contains(DataComponentTypes.JUKEBOX_PLAYABLE)) {
                JukeboxPlayableComponent jukebox = clientStack.get(DataComponentTypes.JUKEBOX_PLAYABLE);
                if (jukebox != null) {
                    Map<String, Object> jbMap = new LinkedHashMap<>();
                    jukebox.song().getKey().ifPresent(key -> 
                        jbMap.put("song", key.getValue().toString())
                    );
                    components.put("minecraft:jukebox_playable", jbMap);
                }
            }

            // --- 17. 烟花 ---
            if (clientStack.contains(DataComponentTypes.FIREWORKS)) {
                FireworksComponent fireworks = clientStack.get(DataComponentTypes.FIREWORKS);
                if (fireworks != null) {
                     dataMap.put("flight", fireworks.flightDuration());
                }
            }

            if (!components.isEmpty()) {
                dataMap.put("components", components);
            }
            
            if (!dataMap.isEmpty()) {
                itemConfig.put("data", dataMap);
            }

        } catch (Exception e) {
            itemConfig.put("_error", "Conversion error: " + e.getMessage());
            itemConfig.put("_error_type", e.getClass().getSimpleName());
            itemConfig.put("material", "barrier");
        }

        return itemConfig;
    }

    // --- 辅助方法 ---

    /**
     * [Fix] 使用 Codec + RegistryOps 序列化 Text，解决 Serializer 类丢失问题
     */
    private static String serializeText(Text text, RegistryWrapper.WrapperLookup registries) {
        if (registries != null) {
            try {
                // 使用 1.21+ 标准 Codec 方式序列化
                // getOps(JsonOps.INSTANCE) 会创建一个带有注册表上下文的 Ops
                return TextCodecs.CODEC.encodeStart(registries.getOps(JsonOps.INSTANCE), text)
                        .map(JsonElement::toString)
                        .result()
                        .orElseGet(() -> "<!i>" + text.getString());
            } catch (Exception e) {
                // 忽略错误，回退到纯文本
            }
        }
        // 没有注册表或发生异常时回退到纯文本
        return "<!i>" + text.getString(); 
    }

    private static ServerPlayerEntity createSafeFakePlayer() {
        try {
            return new ServerPlayerEntity(
                (MinecraftServer) null,
                (ServerWorld) FakeWorld.INSTANCE_UNSAFE,
                new GameProfile(UUID.randomUUID(), "PolymerItemConverter"),
                SyncedClientOptions.createDefault()
            ) {
                @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
            };
        } catch (Exception e) {
            return null;
        }
    }
}