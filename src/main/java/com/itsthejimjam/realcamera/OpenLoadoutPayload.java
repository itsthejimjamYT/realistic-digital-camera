package com.itsthejimjam.realcamera;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: "open my camera body's loadout menu". Sent from the in-photo-mode
 * "ATTACH A LENS" prompt / the inventory key while shooting, so you can swap glass
 * without leaving the finder. {@code tripod} present = the body mounted on that stand;
 * empty = the body in the main hand.
 */
public record OpenLoadoutPayload(Optional<BlockPos> tripod) implements CustomPacketPayload {

	public static final Type<OpenLoadoutPayload> TYPE = new Type<>(PhotoMode.id("open_loadout"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OpenLoadoutPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.optional(BlockPos.STREAM_CODEC), OpenLoadoutPayload::tripod,
			OpenLoadoutPayload::new);

	@Override
	public Type<OpenLoadoutPayload> type() {
		return TYPE;
	}
}
