package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.DyedItemColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerItemConverter");

    private ConverterLogic() {
    }

    public static Map<String, Object> convert(Item registeredItem, PolymerItem polymerItem, ServerLevel level) {
        Map<String, Object> itemConfig = new LinkedHashMap<>();

        PacketContext ctx;
        ItemStack serverStack = new ItemStack(registeredItem);
        ItemStack clientStack;

        try {
            ctx = PacketContext.create(level.registryAccess());
            clientStack = PolymerItemUtils.getPolymerItemStack(serverStack, TooltipFlag.NORMAL, ctx);
            if (clientStack == null || clientStack.isEmpty()) {
                clientStack = serverStack;
            }
        } catch (Throwable t) {
            LOGGER.debug("Falling back to server stack for {}", BuiltInRegistries.ITEM.getKey(registeredItem), t);
            clientStack = serverStack;
            ctx = PacketContext.create();
        }

        Item clientItem = clientStack.getItem();
        itemConfig.put("material", BuiltInRegistries.ITEM.getKey(clientItem).toString());

        Identifier modelId = null;
        try {
            modelId = polymerItem.getPolymerItemModel(serverStack, ctx);
        } catch (Throwable ignored) {
        }
        if (modelId == null && clientStack.has(DataComponents.ITEM_MODEL)) {
            modelId = clientStack.get(DataComponents.ITEM_MODEL);
        }
        if (modelId != null) {
            itemConfig.put("model", modelId.toString());
        }

        Map<String, Object> data = new LinkedHashMap<>();

        Component name = clientStack.getHoverName();
        if (name != null && !name.getString().isEmpty()) {
            data.put("item_name", "<!i>" + name.getString());
        }

        if (clientStack.has(DataComponents.LORE)) {
            ItemLore lore = clientStack.get(DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                List<String> loreLines = new ArrayList<>();
                for (Component line : lore.lines()) {
                    loreLines.add("<!i>" + line.getString());
                }
                data.put("lore", loreLines);
            }
        }

        if (clientStack.has(DataComponents.CUSTOM_MODEL_DATA)) {
            CustomModelData customModelData = clientStack.get(DataComponents.CUSTOM_MODEL_DATA);
            if (customModelData != null && !customModelData.floats().isEmpty()) {
                data.put("custom_model_data", (int) customModelData.floats().getFirst().floatValue());
            }
        }

        if (clientStack.has(DataComponents.MAX_DAMAGE)) {
            data.put("max_damage", clientStack.get(DataComponents.MAX_DAMAGE));
        }
        if (clientStack.has(DataComponents.DAMAGE)) {
            data.put("damage", clientStack.get(DataComponents.DAMAGE));
        }
        if (clientStack.has(DataComponents.REPAIR_COST)) {
            data.put("repair_cost", clientStack.get(DataComponents.REPAIR_COST));
        }
        if (clientStack.has(DataComponents.UNBREAKABLE)) {
            data.put("unbreakable", true);
        }
        if (clientStack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) {
            Boolean glint = clientStack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
            if (glint != null) {
                data.put("enchantment_glint_override", glint);
            }
        }
        if (clientStack.has(DataComponents.DYED_COLOR)) {
            DyedItemColor dyed = clientStack.get(DataComponents.DYED_COLOR);
            if (dyed != null) {
                data.put("dyed_color", String.format(Locale.ROOT, "#%06X", (0xFFFFFF & dyed.rgb())));
            }
        }

        if (!data.isEmpty()) {
            itemConfig.put("data", data);
        }

        if (registeredItem instanceof BlockItem blockItem) {
            Map<String, Object> behavior = new LinkedHashMap<>();
            behavior.put("type", "block_item");
            behavior.put("block", BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
            itemConfig.put("behavior", behavior);
        }

        return itemConfig;
    }
}
