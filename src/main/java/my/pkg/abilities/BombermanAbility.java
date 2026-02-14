package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

public class BombermanAbility implements Ability {

    @Override
    public String id() { return "bomberman"; }

    @Override
    public String name() { return "붐버맨"; }

    @Override
    public int cooldownSeconds() { return 13; } // 원하는대로

    @Override
    public void onGrant(AbilitySystem system, Player player) {
        player.sendMessage("붐버맨 : 능력사용시 사방에 점화된 TNT를 설치합니다. 본인은 모든 폭발피해에 면역입니다!");
    }

    @Override
    public void onDamage(AbilitySystem system, EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean activate(AbilitySystem system, Player player) {
        Location base = player.getLocation().clone();
        Vector forward = base.getDirection().setY(0).normalize();
        if (forward.lengthSquared() < 1e-6) forward = new Vector(0, 0, 1);

        // 오른쪽 벡터 = forward x up
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0)).normalize();

        // 거리(블록) - 너무 붙이면 자폭 느낌 덜나서 보통 1.5~2 추천
        double d = 1.8;

        spawnPrimedTnt(player, base.clone().add(forward.clone().multiply(d)));          // 앞
        spawnPrimedTnt(player, base.clone().add(forward.clone().multiply(-d)));         // 뒤
        spawnPrimedTnt(player, base.clone().add(right.clone().multiply(d)));            // 오른쪽
        spawnPrimedTnt(player, base.clone().add(right.clone().multiply(-d)));           // 왼쪽

        player.getWorld().playSound(base, Sound.ENTITY_TNT_PRIMED, 1.0f, 1.0f);
        return true;
    }

    private void spawnPrimedTnt(Player owner, Location loc) {
        loc.setY(loc.getY() + 0.1); // 바닥에 파묻히는 거 방지용
        TNTPrimed tnt = owner.getWorld().spawn(loc, TNTPrimed.class);
        tnt.setFuseTicks(40);      // 2초 후 폭발 (20틱=1초)
        tnt.setSource(owner);      // 누가 터트렸는지(로그/킬 크레딧)
        tnt.setYield(4.0f);        // 폭발 범위(기본 TNT 느낌)
        tnt.setIsIncendiary(false);
    }


}
