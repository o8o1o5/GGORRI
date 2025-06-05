package dev.o8o1o5.ggorri.items;

import dev.o8o1o5.ggorri.GGORRI;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class CustomItems {
    public static ItemStack createTrackersCompass() {
        ItemStack trackersCompass = new ItemStack(Material.COMPASS);
        ItemMeta meta = trackersCompass.getItemMeta();

        meta.setDisplayName("추적자의 나침반");

        List<String> lore = new ArrayList<>();
        lore.add("날카로운 자침은 복수의 자기장 속에 격동하고 있다.");
        meta.setLore(lore);

        meta.addEnchant(Enchantment.UNBREAKING, 1, true);

        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(GGORRI.getInstance().getCustomItemIdKey(), PersistentDataType.STRING, "trackers_compass");

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ATTRIBUTES);
        trackersCompass.setItemMeta(meta);

        return trackersCompass;
    }
}
