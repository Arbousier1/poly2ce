package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.other.PacketContext;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class LanguageConverterLogic {

    public static Map<String, Object> convert() {
        Map<String, Object> rootConfig = new LinkedHashMap<>();
        
        // 使用 TreeMap 保证键名有序，方便阅读
        Map<String, String> itemLangEn = new TreeMap<>();
        Map<String, String> blockLangEn = new TreeMap<>();
        
        // 虚拟上下文
        PacketContext ctx = PacketContext.create();

        // 1. 提取物品名称 (lang#items)
        for (Identifier id : Registries.ITEM.getIds()) {
            if (id.getNamespace().equals("minecraft")) continue;

            Item item = Registries.ITEM.get(id);
            if (item instanceof PolymerItem) {
                // Key 格式: item.namespace.path
                String key = "item." + id.getNamespace() + "." + id.getPath();
                
                // 核心修复：获取 Polymer 转换后的客户端堆栈
                ItemStack clientStack = PolymerItemUtils.getPolymerItemStack(
                    item.getDefaultStack(), 
                    TooltipType.BASIC, 
                    ctx
                );
                
                // 提取显示名称 (包含 Custom Name 或 Item Name)
                String name = clientStack.getName().getString();
                
                // 添加 <!i> 去除斜体
                itemLangEn.put(key, "<!i>" + name);
            }
        }

        // 2. 提取方块名称 (lang#blocks)
        for (Identifier id : Registries.BLOCK.getIds()) {
            if (id.getNamespace().equals("minecraft")) continue;

            Block block = Registries.BLOCK.get(id);
            if (block instanceof PolymerBlock) {
                // 核心修复：使用 block_name: 前缀
                // CraftEngine 会自动为此生成 block.namespace.path 以及各状态的变体键
                String key = "block_name:" + id.getNamespace() + ":" + id.getPath();
                String name = block.getName().getString();
                
                blockLangEn.put(key, "<!i>" + name);
            }
        }

        // 3. 组装配置结构
        // lang#items
        Map<String, Object> itemsSection = new LinkedHashMap<>();
        itemsSection.put("en_us", itemLangEn);
        itemsSection.put("zh_cn", new LinkedHashMap<>(itemLangEn)); // 复制一份供翻译
        rootConfig.put("lang#items", itemsSection);

        // lang#blocks
        Map<String, Object> blocksSection = new LinkedHashMap<>();
        blocksSection.put("en_us", blockLangEn);
        blocksSection.put("zh_cn", new LinkedHashMap<>(blockLangEn));
        rootConfig.put("lang#blocks", blocksSection);
        
        return rootConfig;
    }
}