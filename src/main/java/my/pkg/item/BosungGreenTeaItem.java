package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class BosungGreenTeaItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    public BosungGreenTeaItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "bosung_green_tea";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.HONEY_BOTTLE);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§a보성녹차");
            meta.setLore(Arrays.asList(
                    "§7마시면 5초 동안 랜덤 버프를 받습니다.",
                    "§8점프강화 / 신속 / 저항 / 돌고래의 우아함"
            ));

            meta.getPersistentDataContainer().set(
                    itemIdKey,
                    PersistentDataType.STRING,
                    id()
            );

            item.setItemMeta(meta);
        }

        return item;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player player, PlayerInteractEvent event) {
        event.setCancelled(true);

        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }

        PotionEffectType picked = randomBuff();

        player.addPotionEffect(new PotionEffect(
                picked,
                20 * 5,
                0,
                false,
                true,
                true
        ));

        player.sendMessage("§a[보성녹차] §f랜덤 버프를 획득했습니다: §e" + getBuffName(picked));
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1f, 1.2f);
    }

    private PotionEffectType randomBuff() {
        List<PotionEffectType> buffs = List.of(
                PotionEffectType.JUMP_BOOST,
                PotionEffectType.SPEED,
                PotionEffectType.RESISTANCE,
                PotionEffectType.DOLPHINS_GRACE
        );

        return buffs.get(ThreadLocalRandom.current().nextInt(buffs.size()));
    }

    private String getBuffName(PotionEffectType type) {
        if (type == PotionEffectType.JUMP_BOOST) return "점프강화";
        if (type == PotionEffectType.SPEED) return "신속";
        if (type == PotionEffectType.RESISTANCE) return "저항";
        if (type == PotionEffectType.DOLPHINS_GRACE) return "돌고래의 우아함";
        return type.getName();
    }
}