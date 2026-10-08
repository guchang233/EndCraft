package dev.skycraft.platform;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
public final class EntityAttributes {
    private static final Map<EntityType<? extends LivingEntity>, AttributeSupplier> ATTRIBUTES = new HashMap<>();
    public static void register(EntityType<? extends LivingEntity> type, AttributeSupplier.Builder attributes) { ATTRIBUTES.put(type, attributes.build()); }
    public static void initialize(IEventBus bus) { bus.addListener((EntityAttributeCreationEvent e) -> ATTRIBUTES.forEach(e::put)); }
}
