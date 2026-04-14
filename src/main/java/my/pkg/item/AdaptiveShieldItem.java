package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.GameMode;

import java.util.List;

public class AdaptiveShieldItem implements SupplyItem {

    private final NamespacedKey itemIdKey;

    private static final double RANGE = 10.0;
    private static final int ABSORB_TICKS = 20 * 15; // 15초
    private static final int MAX_AMP = 4; // 흡수 V 까지

    public AdaptiveShieldItem(NamespacedKey itemIdKey) {
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "adaptive_shield";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack item = new ItemStack(Material.AMETHYST_SHARD, 1);

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e적응형 보호막");
            meta.setLore(List.of(
                    "§7우클릭 시 주변 플레이어 수에 비례해",
                    "§7노란 체력을 얻습니다."
            ));
            meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player player, PlayerInteractEvent event) {
        int nearbyPlayers = 0;

        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)) continue;
            if (!other.isOnline() || other.isDead()) continue;
            if (other.getGameMode() != org.bukkit.GameMode.SURVIVAL) continue;

            if (other.getLocation().distanceSquared(player.getLocation()) <= RANGE * RANGE) {
                nearbyPlayers++;
            }
        }

        int amp = Math.min(MAX_AMP, nearbyPlayers);

        player.addPotionEffect(new PotionEffect(
                PotionEffectType.ABSORPTION,
                ABSORB_TICKS,
                amp,
                false,
                true,
                true
        ));

        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.2f);
        player.getWorld().spawnParticle(
                Particle.TOTEM_OF_UNDYING,
                player.getLocation().add(0, 1.0, 0),
                30, 0.4, 0.6, 0.4, 0.05
        );

        player.sendMessage("§e[적응형 보호막] §f주변 플레이어 §6" + nearbyPlayers + "명 §f감지 → 흡수 §e" + (amp + 1) + "레벨§f 획득!");

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        }

        event.setCancelled(true);
    }

    @Override
    public void onInventoryClick(JavaPlugin plugin, InventoryClickEvent e) {
        // 왼손 관련 처리 삭제
    }

    @Override
    public void onSwapHand(JavaPlugin plugin, PlayerSwapHandItemsEvent event) {
        // 왼손 관련 처리 삭제
    }
}