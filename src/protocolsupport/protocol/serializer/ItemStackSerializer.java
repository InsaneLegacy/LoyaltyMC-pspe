package protocolsupport.protocol.serializer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import protocolsupport.api.ProtocolVersion;
import protocolsupport.api.chat.ChatAPI;
import protocolsupport.api.chat.components.BaseComponent;
import protocolsupport.api.events.ItemStackWriteEvent;
import protocolsupport.protocol.typeremapper.itemstack.ItemStackRemapper;
import protocolsupport.protocol.utils.CommonNBT;
import protocolsupport.protocol.utils.ProtocolVersionsHelper;
import protocolsupport.protocol.utils.types.NetworkItemStack;
import protocolsupport.protocol.utils.types.nbt.NBTCompound;
import protocolsupport.protocol.utils.types.nbt.NBTEnd;
import protocolsupport.protocol.utils.types.nbt.NBTList;
import protocolsupport.protocol.utils.types.nbt.NBTString;
import protocolsupport.protocol.utils.types.nbt.NBTType;
import protocolsupport.protocol.utils.types.nbt.serializer.DefaultNBTSerializer;
import protocolsupport.protocol.utils.types.nbt.serializer.PENBTSerializer;
import protocolsupport.zplatform.ServerPlatform;

public class ItemStackSerializer {

	/**
	 * Reads server itemstack (latest protocol version format) <br>
	 * @param from buffer to read from
	 * @return itemstack
	 */
	public static NetworkItemStack readItemStack(ByteBuf from) {
		if (!from.readBoolean()) {
			return NetworkItemStack.NULL;
		}
		NetworkItemStack itemstack = new NetworkItemStack();
		itemstack.setTypeId(VarNumberSerializer.readVarInt(from));
		itemstack.setAmount(from.readByte());
		itemstack.setNBT(readTag(from));
		return itemstack;
	}

	/**
	 * Reads client itemstack (provided protocol version format) <br>
	 * @param from buffer to read from
	 * @param version protocol version
	 * @param locale client locale
	 * @return itemstack
	 */
	public static NetworkItemStack readItemStack(ByteBuf from, ProtocolVersion version, String locale) {
		int type;
		if (version.isPE()) {
			type = VarNumberSerializer.readSVarInt(from);
		} else if (version.isAfterOrEq(ProtocolVersion.MINECRAFT_1_13_2)) {
			if (!from.readBoolean()) {
				type = -1;
			} else {
				type = VarNumberSerializer.readVarInt(from);
			}
		} else {
			type = from.readShort();
		}
		// PE can have -1 for new blocks :F
		if (version.isPE()) {
			if (type == 0) {
				return NetworkItemStack.NULL;
			}
		} else {
			if (type < 0) {
				return NetworkItemStack.NULL;
			}
		}
		NetworkItemStack itemstack = new NetworkItemStack();
		itemstack.setTypeId(type);
		if (version.isPE()) {
			int amountdata = VarNumberSerializer.readSVarInt(from);
			itemstack.setAmount(amountdata & 0x7F);
			itemstack.setLegacyData((amountdata >> 8) & 0x7FFF);
		} else {
			itemstack.setAmount(from.readByte());
		}
		if (version.isPC() && version.isBefore(ProtocolVersion.MINECRAFT_1_13)) {
			itemstack.setLegacyData(from.readUnsignedShort());
		}
		if (version.isPE()) {
			itemstack.setNBT(readTag(from, false, version));
			//TODO: CanPlaceOn PE
			for (int i = 0; i < VarNumberSerializer.readSVarInt(from); i++) {
				StringSerializer.readString(from, version);
			}
			//TODO: CanDestroy PE
			for (int i = 0; i < VarNumberSerializer.readSVarInt(from); i++) {
				StringSerializer.readString(from, version);
			}
		} else {
			itemstack.setNBT(readTag(from, version));
		}
		itemstack = ItemStackRemapper.remapFromClient(version, locale, itemstack);
		return itemstack;
	}

	/**
	 * Writes server itemstack (latest protocol version format)
	 * @param to buffer to write to
	 * @param itemstack itemstack
	 */
	public static void writeItemStack(ByteBuf to, NetworkItemStack itemstack) {
		if (itemstack.isNull()) {
			to.writeBoolean(false);
		} else {
			to.writeBoolean(true);
			VarNumberSerializer.writeVarInt(to, itemstack.getTypeId());
			to.writeByte(itemstack.getAmount());
			writeTag(to, ProtocolVersionsHelper.LATEST_PC, itemstack.getNBT());
		}
	}

	/**
	 * Writes PE itemstack (latest protocol version format)
	 * @param to buffer to write to
	 * @param itemstack itemstack
	 */
	public static void writePEItemStack(ByteBuf to, NetworkItemStack itemstack) {
		if (itemstack == null || itemstack.isNull()) {
			VarNumberSerializer.writeVarInt(to, 0);
		} else {
			VarNumberSerializer.writeSVarInt(to, itemstack.getTypeId());
			VarNumberSerializer.writeSVarInt(to, ((itemstack.getLegacyData() & 0x7FFF) << 8) | itemstack.getAmount());
			writeTag(to, false, ProtocolVersionsHelper.LATEST_PE, itemstack.getNBT());
			VarNumberSerializer.writeSVarInt(to, 0); //TODO: CanPlaceOn PE
			VarNumberSerializer.writeSVarInt(to, 0); //TODO: CanDestroy PE
		}
	}

	/**
	 * Writes client itemstack (provided protocol version format)
	 * @param to buffer to write to
	 * @param version protocol version
	 * @param locale client locale
	 * @param itemstack itemstack
	 */
	public static void writeItemStack(ByteBuf to, ProtocolVersion version, String locale, NetworkItemStack itemstack) {
		if (itemstack == null || itemstack.isNull()) {
			if (version.isPE()) {
				VarNumberSerializer.writeVarInt(to, 0);
			} else if (version.isAfterOrEq(ProtocolVersion.MINECRAFT_1_13_2)) {
				to.writeBoolean(false);
			} else {
				to.writeShort(-1);
			}
			return;
		}
		itemstack = remapItemToClient(version, locale, itemstack.cloneItemStack());
		if (ItemStackWriteEvent.getHandlerList().getRegisteredListeners().length > 0) {
			ItemStack bukkitStack = ServerPlatform.get().getMiscUtils().createItemStackFromNetwork(itemstack);
			ItemStackWriteEvent event = new ItemStackWriteEvent(version, locale, bukkitStack);
			Bukkit.getPluginManager().callEvent(event);
			List<String> additionalLore = event.getAdditionalLore();
			BaseComponent forcedDisplayName = event.getForcedDisplayName();
			if (forcedDisplayName != null || !additionalLore.isEmpty()) {
				NBTCompound nbt = itemstack.getNBT();
				if (nbt == null) {
					nbt = new NBTCompound();
				}
				NBTCompound displayNBT = CommonNBT.getOrCreateDisplayTag(nbt);

				if (forcedDisplayName != null) {
					displayNBT.setTag(CommonNBT.DISPLAY_NAME, new NBTString(ChatAPI.toJSON(forcedDisplayName)));
				}

				if (!additionalLore.isEmpty()) {
					NBTList<NBTString> loreNBT = displayNBT.getTagListOfType(CommonNBT.DISPLAY_LORE, NBTType.STRING);
					if (loreNBT == null) {
						loreNBT = new NBTList<>(NBTType.STRING);
					}
					for (String lore : additionalLore) {
						loreNBT.addTag(new NBTString(lore));
					}
					displayNBT.setTag(CommonNBT.DISPLAY_LORE, loreNBT);
				}

				itemstack.setNBT(nbt);
			}
		}
		if (version.isPE()) {
			writePEItemStack(to, itemstack);
		} else {
			if (version.isAfterOrEq(ProtocolVersion.MINECRAFT_1_13_2)) {
				to.writeBoolean(true);
				VarNumberSerializer.writeVarInt(to, itemstack.getTypeId());
			} else {
				to.writeShort(itemstack.getTypeId());
			}
			to.writeByte(itemstack.getAmount());
			if (version.isBefore(ProtocolVersion.MINECRAFT_1_13)) {
				to.writeShort(itemstack.getLegacyData());
			}
			writeTag(to, version, itemstack.getNBT());
		}
	}

	/**
	 * Reads server nbt (latest protocol version format)
	 * @param from buffer to read form
	 * @return nbt
	 */
	public static NBTCompound readTag(ByteBuf from) {
		try {
			return DefaultNBTSerializer.INSTANCE.deserializeTag(new ByteBufInputStream(from));
		} catch (IOException e) {
			throw new DecoderException(e);
		}
	}

	/**
	 * Reads client nbt (provided protocol version)
	 * @param from buffer to read form
	 * @param version protocol version
	 * @return nbt
	 */
	public static NBTCompound readTag(ByteBuf from, ProtocolVersion version) {
		return readTag(from, false, version);
	}

	public static NBTCompound readTag(ByteBuf from, boolean varint, ProtocolVersion version) {
		try {
			if (isUsingShortLengthNBT(version)) {
				final short length = from.readShort();
				if (length < 0) {
					return null;
				}
				try (DataInputStream stream = new DataInputStream(new GZIPInputStream(new ByteBufInputStream(from.readSlice(length))))) {
					return DefaultNBTSerializer.INSTANCE.deserializeTag(stream);
				}
			} else if (isUsingDirectNBT(version)) {
				return DefaultNBTSerializer.INSTANCE.deserializeTag(new ByteBufInputStream(from));
			} else if (isUsingPENBT(version)) {
				if (!varint) { // VarInts NBTs doesn't have length
					final short length = from.readShortLE();
					if (length <= 0) {
						return null;
					}
					return PENBTSerializer.LE_INSTANCE.deserializeTag(from);
				}
				return PENBTSerializer.VI_INSTANCE.deserializeTag(from);
			} else {
				throw new IllegalArgumentException(MessageFormat.format("Dont know how to read nbt of version {0}", version));
			}
		} catch (IOException e) {
			throw new DecoderException(e);
		}
	}

	public static void writeTag(ByteBuf to, ProtocolVersion version, NBTCompound tag) {
		writeTag(to, false, version, tag);
	}

	public static void writeTag(ByteBuf to, boolean varint, ProtocolVersion version, NBTCompound tag) {
		if (isUsingShortLengthNBT(version)) {
			if (tag == null) {
				to.writeShort(-1);
			} else {
				MiscSerializer.writeLengthPrefixedBytes(
					to,
					(lTo, length) -> lTo.writeShort(length),
					lTo -> {
						try (DataOutputStream outputstream = new DataOutputStream(new GZIPOutputStream(new ByteBufOutputStream(lTo)))) {
							DefaultNBTSerializer.INSTANCE.serializeTag(outputstream, tag);
						} catch (Exception e) {
							throw new EncoderException(e);
						}
					}
				);
			}
		} else if (isUsingDirectNBT(version)) {
			try (ByteBufOutputStream outputstream = new ByteBufOutputStream(to)) {
				if (tag != null) {
					DefaultNBTSerializer.INSTANCE.serializeTag(outputstream, tag);
				} else {
					DefaultNBTSerializer.INSTANCE.serializeTag(outputstream, NBTEnd.INSTANCE);
				}
			} catch (IOException e) {
				throw new EncoderException(e);
			}
		} else if (isUsingPENBT(version)) {
			if (tag == null) {
				to.writeShortLE(0);
			} else {
				try {
					//fake length
					if (!varint) { // VarInt NBTs doesn't have length
						int writerIndex = to.writerIndex();
						to.writeShortLE(0);
						PENBTSerializer.LE_INSTANCE.serializeTag(to, tag);
						to.setShortLE(writerIndex, to.writerIndex() - writerIndex - Short.BYTES);
					} else {
						PENBTSerializer.VI_INSTANCE.serializeTag(to, tag);
					}
				} catch (Exception e) {
					throw new EncoderException(e);
				}
			}
		} else {
			throw new IllegalArgumentException(MessageFormat.format("Dont know how to write nbt of version {0}", version));
		}
	}

	public static NetworkItemStack remapItemToClient(ProtocolVersion version, String locale, NetworkItemStack itemstack) {
		if (ItemStackWriteEvent.getHandlerList().getRegisteredListeners().length > 0) {
			ItemStack bukkitStack = ServerPlatform.get().getMiscUtils().createItemStackFromNetwork(itemstack);
			ItemStackWriteEvent event = new ItemStackWriteEvent(version, locale, bukkitStack);
			Bukkit.getPluginManager().callEvent(event);
			List<String> additionalLore = event.getAdditionalLore();
			BaseComponent forcedDisplayName = event.getForcedDisplayName();
			if ((forcedDisplayName != null) || !additionalLore.isEmpty()) {
				NBTCompound nbt = itemstack.getNBT();
				if (nbt == null) {
					nbt = new NBTCompound();
				}
				NBTCompound displayNBT = CommonNBT.getOrCreateDisplayTag(nbt);

				if (forcedDisplayName != null) {
					displayNBT.setTag(CommonNBT.DISPLAY_NAME, new NBTString(ChatAPI.toJSON(forcedDisplayName)));
				}

				if (!additionalLore.isEmpty()) {
					NBTList<NBTString> loreNBT = displayNBT.getTagListOfType(CommonNBT.DISPLAY_LORE, NBTType.STRING);
					if (loreNBT == null) {
						loreNBT = new NBTList<>(NBTType.STRING);
					}
					for (String lore : additionalLore) {
						loreNBT.addTag(new NBTString(lore));
					}
					displayNBT.setTag(CommonNBT.DISPLAY_LORE, loreNBT);
				}

				itemstack.setNBT(nbt);
			}
		}
		return ItemStackRemapper.remapToClient(version, locale, itemstack);
	}

	private static final boolean isUsingShortLengthNBT(ProtocolVersion version) {
		return version.isPC() && version.isBeforeOrEq(ProtocolVersion.MINECRAFT_1_7_10);
	}

	private static final boolean isUsingDirectNBT(ProtocolVersion version) {
		return version.isPC() && version.isAfterOrEq(ProtocolVersion.MINECRAFT_1_8);
	}

	private static final boolean isUsingPENBT(ProtocolVersion version) {
		return version.isPE();
	}

}
