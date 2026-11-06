package xyz.thewhish.substratum.neoforge

import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.ModelEvent
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.SubstratumClient
import xyz.thewhish.substratum.neoforge.client.RiftCutNeoForgeModel

object SubstratumNeoForgeClient {

    private val RIFT_CUT = Substratum.id("rift_cut")

    fun init(modBus: IEventBus) {
        SubstratumClient.init()
        modBus.addListener<ModelEvent.ModifyBakingResult> { event ->
            event.models.replaceAll { id, model ->
                if (id.id() == RIFT_CUT) RiftCutNeoForgeModel(model) else model
            }
        }
    }
}
