package my.pkg.abilities;

import my.pkg.AbilitySystem;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

public interface Ability {
    // 내부 식별자 (저장/관리용)
    String id();

    // 표시 이름
    String name();

    // 기본 쿨타임(초)
    int cooldownSeconds();

    // 능력 지급 시 호출
    default void onGrant(AbilitySystem system, Player player) {
    }

    // 능력 회수 시 호출
    default void onRemove(AbilitySystem system, Player player) {
    }

    // 발동 처리 (성공 시 true)
    boolean activate(AbilitySystem system, Player player);


    // ✅ 패시브 훅들 (필요한 것만 override해서 쓰면 됨)
    default void onMove(AbilitySystem system, PlayerMoveEvent event) {}
    default void onDamage(AbilitySystem system, EntityDamageEvent event) {}
    default void onAttack(AbilitySystem system, EntityDamageByEntityEvent event) {}
}
