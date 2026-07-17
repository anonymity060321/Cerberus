package com.yiran.cerberus.passkey

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest

/**
 * HyperOS compatibility bridge.
 *
 * Some Xiaomi builds only expose packages that also declare an AutofillService in the preferred
 * credential service picker. Cerberus does not use this bridge to read, save, or fill passwords;
 * passkey operations continue to be handled exclusively by [CerberusCredentialProviderService].
 */
class CerberusAutofillService : AutofillService() {
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        callback.onSuccess(null)
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback
    ) {
        callback.onSuccess()
    }
}
