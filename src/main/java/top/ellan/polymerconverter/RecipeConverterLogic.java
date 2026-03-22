package top.ellan.polymerconverter;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RecipeConverterLogic {
    private static final Logger LOGGER = LoggerFactory.getLogger("PolymerRecipeConverter");

    private RecipeConverterLogic() {
    }

    public static Map<String, Object> convert(ServerLevel level) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> recipes = new LinkedHashMap<>();
        root.put("recipes", recipes);

        Object recipeManager = level.getServer().getRecipeManager();
        List<Object> holders = collectRecipeHolders(recipeManager);

        for (Object holder : holders) {
            try {
                Identifier recipeId = readIdentifier(holder, "id");
                Object recipe = callNoArg(holder, "value");
                if (recipeId == null || recipe == null) {
                    continue;
                }

                ItemStack result = readResultStack(recipe, level);
                if (result == null || result.isEmpty()) {
                    continue;
                }

                Item outItem = result.getItem();
                Identifier outId = BuiltInRegistries.ITEM.getKey(outItem);
                if (outId == null || !isPolymerItem(outItem)) {
                    continue;
                }

                Map<String, Object> converted = convertRecipe(recipe, recipeId, outId, result.getCount());
                if (!converted.isEmpty()) {
                    recipes.put(recipeId.toString(), converted);
                }
            } catch (Throwable t) {
                LOGGER.debug("Skip recipe holder due to conversion error: {}", t.getMessage());
            }
        }

        return root;
    }

    private static Map<String, Object> convertRecipe(Object recipe, Identifier recipeId, Identifier resultId, int count) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = inferType(recipe);
        out.put("type", type);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", resultId.toString());
        result.put("count", Math.max(1, count));
        out.put("result", result);

        List<Object> ingredients = readIngredients(recipe);
        if (ingredients.isEmpty()) {
            return Map.of();
        }

        if ("shaped".equals(type)) {
            int width = readInt(recipe, "getWidth", "width");
            int height = readInt(recipe, "getHeight", "height");
            if (width <= 0 || height <= 0 || width * height > ingredients.size()) {
                // fallback to shapeless layout when dimensions are not available
                type = "shapeless";
                out.put("type", type);
            } else {
                writeShaped(out, ingredients, width, height);
                return out;
            }
        }

        writeShapeless(out, ingredients);
        return out;
    }

    private static void writeShaped(Map<String, Object> out, List<Object> ingredients, int width, int height) {
        Map<String, Object> ceIngredients = new LinkedHashMap<>();
        List<String> pattern = new ArrayList<>();
        int cursor = 0;
        char symbol = 'A';

        for (int r = 0; r < height; r++) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < width; c++) {
                if (cursor >= ingredients.size()) {
                    row.append(' ');
                    continue;
                }
                List<String> choices = ingredientToItemIds(ingredients.get(cursor++));
                if (choices.isEmpty()) {
                    row.append(' ');
                    continue;
                }
                char key = symbol++;
                row.append(key);
                if (choices.size() == 1) {
                    ceIngredients.put(String.valueOf(key), choices.getFirst());
                } else {
                    ceIngredients.put(String.valueOf(key), choices);
                }
            }
            pattern.add(row.toString());
        }

        if (ceIngredients.isEmpty()) {
            out.clear();
            return;
        }
        out.put("pattern", pattern);
        out.put("ingredients", ceIngredients);
    }

    private static void writeShapeless(Map<String, Object> out, List<Object> ingredients) {
        Map<String, Object> ceIngredients = new LinkedHashMap<>();
        char key = 'A';

        for (Object ingredient : ingredients) {
            List<String> choices = ingredientToItemIds(ingredient);
            if (choices.isEmpty()) {
                continue;
            }
            if (choices.size() == 1) {
                ceIngredients.put(String.valueOf(key), choices.getFirst());
            } else {
                ceIngredients.put(String.valueOf(key), choices);
            }
            key++;
        }

        if (ceIngredients.isEmpty()) {
            out.clear();
            return;
        }
        out.put("ingredients", ceIngredients);
    }

    private static List<String> ingredientToItemIds(Object ingredient) {
        List<String> ids = new ArrayList<>();
        if (ingredient == null) {
            return ids;
        }

        Object stackArray = callNoArg(ingredient, "getItems");
        if (stackArray == null || !stackArray.getClass().isArray()) {
            return ids;
        }

        int len = Array.getLength(stackArray);
        for (int i = 0; i < len; i++) {
            Object value = Array.get(stackArray, i);
            if (!(value instanceof ItemStack stack) || stack.isEmpty()) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

    private static boolean isPolymerItem(Item item) {
        if (item instanceof PolymerItem) {
            return true;
        }
        return PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item) instanceof PolymerItem;
    }

    private static String inferType(Object recipe) {
        String name = recipe.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (name.contains("shaped")) return "shaped";
        if (name.contains("shapeless")) return "shapeless";
        if (name.contains("smelting")) return "smelting";
        if (name.contains("blasting")) return "blasting";
        if (name.contains("smoking")) return "smoking";
        if (name.contains("campfire")) return "campfire_cooking";
        if (name.contains("stonecut")) return "stone_cutting";
        if (name.contains("smithing")) return "smithing";
        return "shapeless";
    }

    private static List<Object> collectRecipeHolders(Object manager) {
        List<Object> out = new ArrayList<>();

        Object fromGetRecipes = callNoArg(manager, "getRecipes");
        if (fromGetRecipes != null) {
            flattenRecipeCandidates(fromGetRecipes, out);
        }
        Object fromValues = callNoArg(manager, "values");
        if (fromValues != null) {
            flattenRecipeCandidates(fromValues, out);
        }

        if (!out.isEmpty()) {
            return out;
        }

        for (Field field : manager.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(manager);
                if (value != null) {
                    flattenRecipeCandidates(value, out);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void flattenRecipeCandidates(Object value, List<Object> out) {
        if (value == null) {
            return;
        }

        if (isRecipeHolder(value)) {
            out.add(value);
            return;
        }

        if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                flattenRecipeCandidates(v, out);
            }
            return;
        }

        if (value instanceof Collection<?> collection) {
            for (Object v : collection) {
                flattenRecipeCandidates(v, out);
            }
        }
    }

    private static boolean isRecipeHolder(Object value) {
        if (value == null) {
            return false;
        }
        String name = value.getClass().getSimpleName();
        if (name.contains("RecipeHolder")) {
            return true;
        }
        return hasNoArgMethod(value, "id") && hasNoArgMethod(value, "value");
    }

    private static List<Object> readIngredients(Object recipe) {
        Object ingredients = callNoArg(recipe, "getIngredients");
        if (ingredients instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (ingredients instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of();
    }

    private static ItemStack readResultStack(Object recipe, ServerLevel level) {
        Object result = callOneArg(recipe, "getResultItem", level.registryAccess());
        if (result instanceof ItemStack stack) {
            return stack;
        }
        result = callNoArg(recipe, "result");
        if (result instanceof ItemStack stack) {
            return stack;
        }
        result = callNoArg(recipe, "getResultItem");
        if (result instanceof ItemStack stack) {
            return stack;
        }
        return ItemStack.EMPTY;
    }

    private static Identifier readIdentifier(Object target, String methodName) {
        Object value = callNoArg(target, methodName);
        if (value instanceof Identifier id) {
            return id;
        }
        return null;
    }

    private static int readInt(Object target, String... names) {
        for (String name : names) {
            Object value = callNoArg(target, name);
            if (value instanceof Integer i) {
                return i;
            }
        }
        return -1;
    }

    private static Object callNoArg(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callOneArg(Object target, String name, Object arg) {
        if (target == null || arg == null) {
            return null;
        }
        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> param = method.getParameterTypes()[0];
            if (!param.isAssignableFrom(arg.getClass())) {
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean hasNoArgMethod(Object target, String name) {
        if (target == null) {
            return false;
        }
        try {
            target.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
