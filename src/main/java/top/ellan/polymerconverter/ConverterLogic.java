package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.other.PacketContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.*;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.item.equipment.ArmorTrim;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

import java.util.*;

public class ConverterLogic {

    public static Map<String, Object> convert(PolymerItem polymerItem) {
        Map<String, Object> itemConfig = new LinkedHashMap<>();
        
        // 1. 准备环境
        ItemStack serverStack = new ItemStack((Item) polymerItem);
        // 静态导出时没有真实玩家，使用空上下文
        PacketContext ctx = PacketContext.create(); 

        try {
            // --- A. 基础材质与模型 ---
            Item clientBaseItem = polymerItem.getPolymerItem(serverStack, ctx);
            itemConfig.put("material", Registries.ITEM.getId(clientBaseItem).toString());

            // Polymer Model
            Identifier modelId = polymerItem.getPolymerItemModel(serverStack, ctx);
            if (modelId != null) {
                Map<String, Object> modelData = new LinkedHashMap<>();
                modelData.put("type", "minecraft:model");
                modelData.put("path", modelId.toString());
                itemConfig.put("model", modelData);
            }

            // --- B. 获取完整的客户端堆栈 ---
            ItemStack clientStack = PolymerItemUtils.getPolymerItemStack(serverStack, TooltipType.BASIC, ctx);
            
            // CraftEngine 配置结构：
            // data:
            //   item-name: ...
            //   lore: ...
            //   components: 
            //     minecraft:food: ...
            Map<String, Object> dataMap = new LinkedHashMap<>();
            Map<String, Object> components = new LinkedHashMap<>(); // 专门存放结构化组件

            // 1. 名称 (Item Name)
            if (clientStack.hasCustomName()) {
                dataMap.put("item-name", "<!i>" + clientStack.getName().getString());
            }

            // 2. 描述 (Lore)
            if (clientStack.contains(DataComponentTypes.LORE)) {
                LoreComponent lore = clientStack.get(DataComponentTypes.LORE);
                if (lore != null) {
                    List<String> loreLines = new ArrayList<>();
                    for (Text line : lore.lines()) {
                        loreLines.add("<!i>" + line.getString());
                    }
                    dataMap.put("lore", loreLines);
                }
            }

            // 3. 自定义模型数据 (CMD) - 兼容 1.21.4+
            if (clientStack.contains(DataComponentTypes.CUSTOM_MODEL_DATA)) {
                Object cmdValue = clientStack.get(DataComponentTypes.CUSTOM_MODEL_DATA).value();
                if (cmdValue instanceof Integer intVal) {
                    itemConfig.put("custom-model-data", intVal);
                } else if (cmdValue instanceof List<?> list && !list.isEmpty()) {
                    Object first = list.get(0);
                    if (first instanceof Number num) {
                        itemConfig.put("custom-model-data", (int) num.floatValue());
                    }
                }
            }
            
            // 4. Item Model 组件 (1.21.4+)
            if (!itemConfig.containsKey("model") && clientStack.contains(DataComponentTypes.ITEM_MODEL)) {
                Identifier itemModelId = clientStack.get(DataComponentTypes.ITEM_MODEL);
                if (itemModelId != null) {
                    Map<String, Object> modelData = new LinkedHashMap<>();
                    modelData.put("type", "minecraft:model");
                    modelData.put("path", itemModelId.toString());
                    itemConfig.put("model", modelData);
                }
            }

            // 5. 附魔 (Enchantments)
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

            // 6. 属性修饰符 (Attribute Modifiers) -> 放入 components
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
                    
                    // 标准 NBT 结构格式
                    Map<String, Object> attrComp = new LinkedHashMap<>();
                    attrComp.put("modifiers", attrList);
                    components.put("minecraft:attribute_modifiers", attrComp);
                }
            }

            // 7. 染色 (Dyed Color)
            if (clientStack.contains(DataComponentTypes.DYED_COLOR)) {
                DyedColorComponent dyedColor = clientStack.get(DataComponentTypes.DYED_COLOR);
                if (dyedColor != null) {
                    int rgb = dyedColor.rgb();
                    String hex = String.format("#%06X", (0xFFFFFF & rgb));
                    dataMap.put("dyed-color", hex);
                }
            }

            // 8. 附魔光效 (Glint)
            if (clientStack.contains(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)) {
                Boolean glint = clientStack.get(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
                if (glint != null) {
                    dataMap.put("enchantment-glint-override", glint);
                }
            }

            // 9. 物理属性 (Unbreakable / Damage)
            if (clientStack.contains(DataComponentTypes.UNBREAKABLE)) {
                dataMap.put("unbreakable", true);
            }
            if (clientStack.contains(DataComponentTypes.MAX_DAMAGE)) {
                dataMap.put("max-damage", clientStack.get(DataComponentTypes.MAX_DAMAGE));
            }
            if (clientStack.contains(DataComponentTypes.DAMAGE)) {
                 dataMap.put("damage", clientStack.get(DataComponentTypes.DAMAGE));
            }

            // 10. 修复成本 (Repair Cost)
            if (clientStack.contains(DataComponentTypes.REPAIR_COST)) {
                 dataMap.put("repair-cost", clientStack.get(DataComponentTypes.REPAIR_COST));
            }

            // 11. 食物属性 (Food) -> 放入 components
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

            // 12. 稀有度 (Rarity)
            if (clientStack.contains(DataComponentTypes.RARITY)) {
                Rarity rarity = clientStack.get(DataComponentTypes.RARITY);
                if (rarity != null) {
                    dataMap.put("rarity", rarity.name().toLowerCase());
                }
            }

            // 13. Tooltip 样式 (1.21.2+)
            if (clientStack.contains(DataComponentTypes.TOOLTIP_STYLE)) {
                Identifier style = clientStack.get(DataComponentTypes.TOOLTIP_STYLE);
                if (style != null) {
                    dataMap.put("tooltip-style", style.toString());
                }
            }

            // 14. 盔甲纹饰 (Trim) -> 放入 components
            if (clientStack.contains(DataComponentTypes.TRIM)) {
                ArmorTrim trim = clientStack.get(DataComponentTypes.TRIM);
                if (trim != null) {
                    Map<String, Object> trimMap = new LinkedHashMap<>();
                    trimMap.put("material", trim.material().value().getIdAsString());
                    trimMap.put("pattern", trim.pattern().value().getIdAsString());
                    components.put("minecraft:trim", trimMap);
                }
            }

            // 15. 装备属性 (Equippable) -> 放入 components
            if (clientStack.contains(DataComponentTypes.EQUIPPABLE)) {
                EquippableComponent equippable = clientStack.get(DataComponentTypes.EQUIPPABLE);
                if (equippable != null) {
                    Map<String, Object> eqMap = new LinkedHashMap<>();
                    eqMap.put("slot", equippable.slot().asString());
                    if (equippable.model().isPresent()) {
                        eqMap.put("model", equippable.model().get().toString());
                    }
                    // eqMap.put("camera_overlay", ...); // 可选
                    components.put("minecraft:equippable", eqMap);
                }
            }

            // --- 组装结果 ---
            if (!components.isEmpty()) {
                // 将结构化组件放入 components 节点
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
}