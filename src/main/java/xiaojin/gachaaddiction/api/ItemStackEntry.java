package xiaojin.gachaaddiction.api;

import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import xiaojin.gachaaddiction.mixin.accessor.LootPoolAccessor;
import xiaojin.gachaaddiction.mixin.accessor.LootTableAccessor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public record ItemStackEntry(ItemStack stack, int weight) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ItemStackEntry> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, ItemStackEntry::stack,
            ByteBufCodecs.VAR_INT, ItemStackEntry::weight,
            ItemStackEntry::new);

    public static final StreamCodec<RegistryFriendlyByteBuf, List<ItemStackEntry>> LIST_STREAM_CODEC =
            STREAM_CODEC.apply(ByteBufCodecs.list());

    /**
     * 26.3 把 {@code LootPoolSingletonContainer} 拆成了 {@code UniformContainerBase} /
     * {@code SingleEntryContainerBase}，{@code weight} 字段的归属类变了。
     * 26.x 的运行时名即官方名，所以这里沿继承链按名字反射读取，
     * 不再依赖具体的父类，今后再拆分也不会让 Mixin 在加载时崩溃。
     */
    private static final java.util.Map<Class<?>, java.util.Optional<Field>> WEIGHT_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, java.util.Optional<Field>> ITEM_FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.util.Optional<Field> findField(Class<?> start, String name, Class<?> type) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name) && type.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return java.util.Optional.of(f);
                }
            }
        }
        return java.util.Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Holder<Item> readItem(LootItem lootItem) {
        try {
            Field f = ITEM_FIELDS.computeIfAbsent(lootItem.getClass(), c -> findField(c, "item", Holder.class)).orElse(null);
            return f == null ? null : (Holder<Item>) f.get(lootItem);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static int readWeight(LootPoolEntryContainer entry) {
        try {
            Field f = WEIGHT_FIELDS.computeIfAbsent(entry.getClass(), c -> findField(c, "weight", int.class)).orElse(null);
            return f == null ? 1 : f.getInt(entry);
        } catch (ReflectiveOperationException e) {
            return 1;
        }
    }

    public static List<ItemStackEntry> extract(LootTable table) {
        List<ItemStackEntry> result = new ArrayList<>();
        for (LootPool pool : ((LootTableAccessor) table).gachaaddiction$getPools()) {
            for (LootPoolEntryContainer entry : ((LootPoolAccessor) pool).gachaaddiction$getEntries()) {
                if (entry instanceof LootItem lootItem) {
                    Holder<Item> holder = readItem(lootItem);
                    if (holder == null) continue;
                    result.add(new ItemStackEntry(new ItemStack(holder), readWeight(lootItem)));
                }
            }
        }
        return result;
    }

    public static List<ItemStack> generateGachaResult(List<ItemStackEntry> entries, int totalCount, RandomSource random) {
        if (totalCount <= 0) return new ArrayList<>();
        int totalWeight = entries.stream().mapToInt(ItemStackEntry::weight).sum();
        if (totalWeight <= 0) return new ArrayList<>();

        List<ItemStack> result = new ArrayList<>(totalCount);
        for (int i = 0; i < totalCount; i++) {
            int roll = random.nextInt(totalWeight);
            int accumulated = 0;
            for (ItemStackEntry entry : entries) {
                accumulated += entry.weight();
                if (roll < accumulated) {
                    result.add(entry.stack().copy());
                    break;
                }
            }
        }
        return result;
    }
}
