package protocolsupport.protocol.packet.middle.clientbound.play;

import io.netty.buffer.ByteBuf;
import protocolsupport.api.chat.ChatAPI;
import protocolsupport.api.chat.components.BaseComponent;
import protocolsupport.protocol.ConnectionImpl;
import protocolsupport.protocol.packet.middle.ClientBoundMiddlePacket;
import protocolsupport.protocol.serializer.StringSerializer;
import protocolsupport.protocol.typeremapper.basic.GenericIdSkipper;
import protocolsupport.protocol.utils.types.WindowType;
import protocolsupport.zplatform.ServerPlatform;

public abstract class MiddleInventoryOpen extends ClientBoundMiddlePacket {

	public MiddleInventoryOpen(ConnectionImpl connection) {
		super(connection);
	}

	protected int windowId;
	protected WindowType type;
	protected BaseComponent title;
	protected int slots;
	protected int horseId;

	@Override
	public void readFromServerData(ByteBuf serverdata) {
		windowId = serverdata.readUnsignedByte();
		type = WindowType.getById(StringSerializer.readVarIntUTF8String(serverdata));
		title = ChatAPI.fromJSON(StringSerializer.readVarIntUTF8String(serverdata));
		slots = serverdata.readUnsignedByte();
		if (type == WindowType.HORSE) {
			horseId = serverdata.readInt();
		} else {
			horseId = -1;
		}
	}

	@Override
	public boolean postFromServerRead() {
		int invSlots = slots;
		switch (type) {
			case ANVIL: {
				invSlots = 3;
				break;
			}
			case BEACON: {
				invSlots = 1;
				break;
			}
			case CRAFTING_TABLE: {
				invSlots = 10;
				break;
			}
			case ENCHANT: {
				invSlots = 2;
				break;
			}
			default: {
				break;
			}
		}
		cache.getWindowCache().setOpenedWindow(type, windowId, invSlots, horseId);
		if (GenericIdSkipper.INVENTORY.getTable(connection.getVersion()).shouldSkip(type)) {
			connection.receivePacket(ServerPlatform.get().getPacketFactory().createInboundInventoryClosePacket());
			return false;
		} else {
			return true;
		}
	}
}
