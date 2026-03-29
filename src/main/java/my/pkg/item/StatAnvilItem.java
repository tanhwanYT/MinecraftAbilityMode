package my.pkg.item;

import my.pkg.SupplyItem;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class StatAnvilItem implements SupplyItem {

    private final JavaPlugin plugin;
    private final NamespacedKey itemIdKey;

    public StatAnvilItem(JavaPlugin plugin, NamespacedKey itemIdKey) {
        this.plugin = plugin;
        this.itemIdKey = itemIdKey;
    }

    @Override
    public String id() {
        return "stat_anvil";
    }

    @Override
    public ItemStack create(JavaPlugin plugin) {
        ItemStack it = new ItemStack(Material.ANVIL, 1);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        meta.setDisplayName("§d능력치 모루");
        meta.setLore(List.of(
                "§7우클릭 시 랜덤 능력치 1개를 획득합니다.",
                "§e가능 스탯: 체력, 공격력, 속도, 밀치기 저항"
        ));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, id());
        it.setItemMeta(meta);
        return it;
    }

    @Override
    public void onRightClick(JavaPlugin plugin, Player p, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        int roll = ThreadLocalRandom.current().nextInt(4);

        switch (roll) {
            case 0 -> giveMaxHealth(p);
            case 1 -> giveAttackDamage(p);
            case 2 -> giveMoveSpeed(p);
            case 3 -> giveKnockbackResistance(p);
        }

        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1.0f, 1.15f);
        p.getWorld().spawnParticle(Particle.ENCHANT, p.getLocation().add(0, 1, 0), 30, 0.4, 0.5, 0.4, 0.0);

        consumeOne(e);
        e.setCancelled(true);
    }

    private void giveMaxHealth(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        double before = attr.getBaseValue();
        double after = Math.min(before + 2.0, 40.0); // +1칸, 최대 20칸(40.0)
        attr.setBaseValue(after);

        // 증가한 만큼 현재 체력도 같이 회복
        p.setHealth(Math.min(p.getHealth() + (after - before), after));
        p.sendMessage("§d[능력치 모루] §f최대 체력이 §c1칸§f 증가했습니다!");
    }

    private void giveAttackDamage(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attr == null) {
            p.sendMessage("§c[능력치 모루] 공격력 스탯을 적용할 수 없습니다.");
            return;
        }

        double before = attr.getBaseValue();
        double after = Math.min(before + 1.0, 20.0); // +1 공격력
        attr.setBaseValue(after);

        p.sendMessage("§d[능력치 모루] §f공격력이 §c1§f 증가했습니다!");
    }

    private void giveMoveSpeed(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) return;

        double before = attr.getBaseValue();
        double after = Math.min(before + 0.015, 0.30); // 적당히 소폭 증가
        attr.setBaseValue(after);

        p.sendMessage("§d[능력치 모루] §f이동속도가 §b증가§f했습니다!");
    }

    private void giveKnockbackResistance(Player p) {
        AttributeInstance attr = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (attr == null) return;

        double before = attr.getBaseValue();
        double after = Math.min(before + 0.1, 1.0); // 10%씩 증가
        attr.setBaseValue(after);

        p.sendMessage("§d[능력치 모루] §f밀치기 저항이 §6증가§f했습니다!");
    }

    private void consumeOne(PlayerInteractEvent e) {
        ItemStack hand = e.getItem();
        if (hand == null) return;

        int amt = hand.getAmount();
        if (amt <= 1) {
            hand.setAmount(0);
        } else {
            hand.setAmount(amt - 1);
        }
    }
}