package com.itsthejimjam.realcamera;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: "open my camera body's loadout menu". Sent from the in-photo-mode
 * "ATTACH A LENS" prompt / the inventory key while shooting, so you can swap glass
 * without leaving the finder. {@code tripod} present = the body mounted on that stand;
 * empty = the body in the main hand.
 *
 * <p>1.20.4 has no {@code CustomPacketPayload}/codec system yet, so this is a plain
 * channel id + raw {@link FriendlyByteBuf} read/write instead of a record type.
 */
public final class OpenLoadoutPayload {

	public static final ResourceLocation CHANNEL = PhotoMode.id("open_loadout");

	private OpenLoadoutPayload() {
	}

	public static void write(FriendlyByteBuf buf, Optional<BlockPos> tripod) {
		buf.writeBoolean(tripod.isPresent());
		tripod.ifPresent(buf::writeBlockPos);
	}

	public static Optional<BlockPos> read(FriendlyByteBuf buf) {
		return buf.readBoolean() ? Optional.of(buf.readBlockPos()) : Optional.empty();
	}
}
