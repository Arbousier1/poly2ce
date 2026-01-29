package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerUtils;
import xyz.nucleoid.packettweaker.PacketContext;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

@SuppressWarnings({"null"})
public class ConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerItemConverter");
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /**
     * 转换物品逻辑
     * @param registeredItem 实际注册的物品
     * @param polymerItem    Polymer 逻辑接口
     * @param level          服务器世界上下文 (用于创建 FakePlayer)
     */
    public static Map<String, Object> convert(Item registeredItem, PolymerItem polymerItem, ServerLevel level) {
        Map<String, Object> itemConfig = new LinkedHashMap<>();
        
        if (registeredItem == null || polymerItem == null) {
             itemConfig.put("_error", "Input item or logic cannot be null");
             return itemConfig;
        }

        ItemStack serverStack = new ItemStack(registeredItem);
        
        // =================================================================================
        // 上下文创建与客户端堆栈获取 (带 NPE 降级保护)
        // =================================================================================
        PacketContext ctx;
        ItemStack clientStack;
        ServerPlayer fakePlayer = null;

        try {
            fakePlayer = createSafeFakePlayer(level);
            if (fakePlayer != null) {
                ctx = PacketContext.create(fakePlayer);
            } else {
                // 降级：无玩家上下文
                ctx = PacketContext.create();
            }
            
            TooltipFlag tooltipType = fakePlayer != null ? 
                PolymerUtils.getTooltipType(fakePlayer) : TooltipFlag.Default.NORMAL;
            
            clientStack = PolymerItemUtils.getPolymerItemStack(serverStack, tooltipType, ctx);
            
            if (clientStack == null || clientStack.isEmpty()) {
                throw new IllegalStateException("getPolymerItemStack returned null/empty");
            }
        } catch (Exception e) {
            itemConfig.put("_error", "Context/Stack creation failed: " + e.getMessage());
            itemConfig.put("material", "barrier");
            return itemConfig;
        }
        // =================================================================================

        // --- A. 基础材质 (Material) ---
        Item clientItem = clientStack.getItem();
        itemConfig.put("material", BuiltInRegistries.ITEM.getKey(clientItem).toString());

        // --- B. 模型 (Model) ---
        // 尝试从 Polymer 逻辑获取模型 ID
        Identifier modelId = polymerItem.getPolymerItemModel(serverStack, ctx);
        // 或者从 1.21.2+ 的 DataComponent 获取
        if (modelId == null && clientStack.has(DataComponents.ITEM_MODEL)) {
            modelId = clientStack.get(DataComponents.ITEM_MODEL);
        }

        if (modelId != null) {
            Map<String, Object> modelData = new LinkedHashMap<>();
            modelData.put("type", "minecraft:model");
            modelData.put("path", modelId.toString());
            itemConfig.put("model", modelData);
        }

        // --- C. 物品数据 (Data) ---
        Map<String, Object> dataMap = new LinkedHashMap<>();
        Map<String, Object> components = new LinkedHashMap<>();
        HolderLookup.Provider registryLookup = fakePlayer != null ? fakePlayer.registryAccess() : null;

        // 1. 名称 (Item Name / Custom Name)
        if (clientStack.has(DataComponents.CUSTOM_NAME)) {
            Component nameText = clientStack.getHoverName();
            dataMap.put("item-name", serializeText(nameText, registryLookup));
        } else if (clientStack.has(DataComponents.ITEM_NAME)) {
             Component nameText = clientStack.get(DataComponents.ITEM_NAME);
             dataMap.put("item-name", serializeText(nameText, registryLookup));
        }

        // 2. 描述 (Lore)
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

        // 3. CustomModelData
        if (clientStack.has(DataComponents.CUSTOM_MODEL_DATA)) {
            CustomModelData cmd = clientStack.get(DataComponents.CUSTOM_MODEL_DATA);
            if (cmd != null) {
                if (!cmd.floats().isEmpty()) {
                    // CE 历史遗留：custom-model-data 通常指第一个 float 转 int
                    dataMap.put("custom-model-data", (int) cmd.floats().getFirst().floatValue());
                }
            }
        }

        // 4. 附魔 (Enchantments)
        if (clientStack.has(DataComponents.ENCHANTMENTS)) {
            var enchants = clientStack.get(DataComponents.ENCHANTMENTS);
            if (enchants != null && !enchants.isEmpty()) {
                Map<String, Integer> enchConfig = new LinkedHashMap<>();
                enchants.entrySet().forEach(entry -> {
                    entry.getKey().unwrapKey().ifPresent(key -> {
                        enchConfig.put(key.identifier().toString(), entry.getIntValue());
                    });
                });
                if (!enchConfig.isEmpty()) {
                    dataMap.put("enchantment", enchConfig);
                }
            }
        }

        // 5. 属性修饰符 (Attribute Modifiers)
        if (clientStack.has(DataComponents.ATTRIBUTE_MODIFIERS)) {
            ItemAttributeModifiers attrs = clientStack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (attrs != null && !attrs.modifiers().isEmpty()) {
                List<Map<String, Object>> attrList = new ArrayList<>();
                attrs.modifiers().forEach(entry -> {
                    Map<String, Object> attrMap = new LinkedHashMap<>();
                    entry.attribute().unwrapKey().ifPresent(key -> attrMap.put("type", key.identifier().toString()));
                    attrMap.put("slot", entry.slot().getSerializedName());
                    attrMap.put("amount", entry.modifier().amount());
                    attrMap.put("operation", entry.modifier().operation().name().toLowerCase());
                    attrMap.put("id", entry.modifier().id().toString());
                    attrList.add(attrMap);
                });
                dataMap.put("attribute-modifiers", attrList);
            }
        }

        // 6. 染色 (Dyed Color)
        if (clientStack.has(DataComponents.DYED_COLOR)) {
            DyedItemColor dyedColor = clientStack.get(DataComponents.DYED_COLOR);
            if (dyedColor != null) {
                int rgb = dyedColor.rgb();
                dataMap.put("dyed-color", String.format(Locale.ROOT, "#%06X", (0xFFFFFF & rgb)));
            }
        }

        // 7. 杂项布尔/数值属性
        if (clientStack.has(DataComponents.UNBREAKABLE)) dataMap.put("unbreakable", true);
        if (clientStack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) {
            Boolean glint = clientStack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
            if (glint != null) dataMap.put("enchantment-glint-override", glint);
        }
        if (clientStack.has(DataComponents.MAX_DAMAGE)) dataMap.put("max-damage", clientStack.get(DataComponents.MAX_DAMAGE));
        if (clientStack.has(DataComponents.DAMAGE)) dataMap.put("damage", clientStack.get(DataComponents.DAMAGE));
        if (clientStack.has(DataComponents.REPAIR_COST)) dataMap.put("repair-cost", clientStack.get(DataComponents.REPAIR_COST));
        if (clientStack.has(DataComponents.RARITY)) {
            Rarity rarity = clientStack.get(DataComponents.RARITY);
            if (rarity != null) dataMap.put("rarity", rarity.name().toLowerCase());
        }

        // 8. 复杂组件 (Components) - 映射到 data.components
        
        // Food
        if (clientStack.has(DataComponents.FOOD)) {
            FoodProperties food = clientStack.get(DataComponents.FOOD);
            if (food != null) {
                Map<String, Object> foodMap = new LinkedHashMap<>();
                foodMap.put("nutrition", food.nutrition());
                foodMap.put("saturation", food.saturation());
                foodMap.put("can_always_eat", food.canAlwaysEat());
                // CE 支持在 data 下直接写 food
                dataMap.put("food", foodMap);
            }
        }

        // Tool
        if (clientStack.has(DataComponents.TOOL)) {
            Tool tool = clientStack.get(DataComponents.TOOL);
            if (tool != null) {
                Map<String, Object> toolMap = new LinkedHashMap<>();
                toolMap.put("default_mining_speed", tool.defaultMiningSpeed());
                toolMap.put("damage_per_block", tool.damagePerBlock());
                components.put("minecraft:tool", toolMap);
            }
        }

        // Jukebox Playable
        if (clientStack.has(DataComponents.JUKEBOX_PLAYABLE)) {
            JukeboxPlayable jukebox = clientStack.get(DataComponents.JUKEBOX_PLAYABLE);
            if (jukebox != null && jukebox.song().key().isPresent()) {
                dataMap.put("jukebox-playable", jukebox.song().key().get().identifier().toString());
            }
        }

        // Trim
        if (clientStack.has(DataComponents.TRIM)) {
            var trim = clientStack.get(DataComponents.TRIM);
            if (trim != null) {
                Map<String, Object> trimMap = new LinkedHashMap<>();
                trim.material().unwrapKey().ifPresent(k -> trimMap.put("material", k.identifier().toString()));
                trim.pattern().unwrapKey().ifPresent(k -> trimMap.put("pattern", k.identifier().toString()));
                dataMap.put("trim", trimMap);
            }
        }

        // Equippable (1.21.2+)
        if (clientStack.has(DataComponents.EQUIPPABLE)) {
            var eq = clientStack.get(DataComponents.EQUIPPABLE);
            if (eq != null) {
                Map<String, Object> eqMap = new LinkedHashMap<>();
                eqMap.put("slot", eq.slot().getSerializedName());
                eq.assetId().ifPresent(id -> eqMap.put("asset-id", id.toString())); // CE use asset-id
                eq.cameraOverlay().ifPresent(id -> eqMap.put("camera-overlay", id.toString()));
                eqMap.put("damage-on-hurt", eq.damageOnHurt());
                eqMap.put("dispensable", eq.dispensable());
                eqMap.put("swappable", eq.swappable());
                dataMap.put("equippable", eqMap);
            }
        }

        // Tooltip Style
        if (clientStack.has(DataComponents.TOOLTIP_STYLE)) {
            Identifier style = clientStack.get(DataComponents.TOOLTIP_STYLE);
            if (style != null) dataMap.put("tooltip-style", style.toString());
        }

        // --- 组装 Data ---
        if (!components.isEmpty()) {
            dataMap.put("components", components);
        }
        if (!dataMap.isEmpty()) {
            itemConfig.put("data", dataMap);
        }

        // --- D. 物品设置 (Settings) ---
        Map<String, Object> settings = new LinkedHashMap<>();
        
        // 1. 标签 (Tags) [修复：解决 deprecated 警告]
        Optional<ResourceKey<Item>> resourceKey = BuiltInRegistries.ITEM.getResourceKey(registeredItem);
        if (resourceKey.isPresent()) {
             Optional<Holder.Reference<Item>> holder = BuiltInRegistries.ITEM.get(resourceKey.get());
             if (holder.isPresent()) {
                 List<String> tags = holder.get().tags().map(tag -> tag.location().toString()).toList();
                 if (!tags.isEmpty()) {
                     settings.put("tags", tags);
                 }
             }
        }

        // 2. 杂项设置 [修复：解决 isFireResistant 错误]
        // 1.20.5+ 防火属性存在于 DataComponent 中
        if (serverStack.has(DataComponents.DAMAGE_RESISTANT)) {
            var resistance = serverStack.get(DataComponents.DAMAGE_RESISTANT);
            if (resistance.types().location().getPath().contains("fire")) {
                settings.put("invulnerable", List.of("fire", "lava"));
            }
        }
        
        if (clientStack.isDamageableItem()) {
            settings.put("repairable", true);
        }

        if (!settings.isEmpty()) {
            itemConfig.put("settings", settings);
        }

        // --- E. 行为 (Behavior) ---
        // 自动识别 BlockItem 并转换为 block_item 行为
        if (registeredItem instanceof BlockItem blockItem) {
            Map<String, Object> behavior = new LinkedHashMap<>();
            behavior.put("type", "block_item");
            behavior.put("block", BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
            itemConfig.put("behavior", behavior);
        }

        return itemConfig;
    }

    private static String serializeText(Component text, HolderLookup.Provider registries) {
        if (text == null) return "";
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

    private static ServerPlayer createSafeFakePlayer(ServerLevel level) {
        if (level == null) return null;
        try {
            return new ServerPlayer(
                level.getServer(),
                level,
                new GameProfile(NIL_UUID, "PolymerItemConverter"),
                ClientInformation.createDefault()
            ) {
                @Override public boolean isSpectator() { return false; }
                @Override public boolean isCreative() { return false; }
            };
        } catch (Exception e) {
            LOGGER.error("Failed to create fake player for item conversion", e);
            return null;
        }
    }
}