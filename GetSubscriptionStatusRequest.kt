// File: GetSubscriptionStatusRequest.kt

package app.yoword.requests

import app.yoword.YoWordApplication
import app.yoword.BuildConfig
import app.yoword.constants.BASE_GOOGLE_PLAY_PRODUCT_ID
import app.yoword.constants.MAX_KARMA_FOR_ADS
import app.yoword.constants.SUBSCRIPTION_PROVIDER_GOOGLE
import app.yoword.constants.SUBSCRIPTION_STATE_ACTIVE
import app.yoword.constants.SUBSCRIPTION_STATE_CANCELED
import app.yoword.constants.SUBSCRIPTION_STATE_GRACE_PERIOD
import app.yoword.constants.SUBSCRIPTION_STATE_PAUSED
import app.yoword.constants.SUBSCRIPTION_STATE_PENDING
import app.yoword.constants.SUBSCRIPTION_STATE_REPAIR
import app.yoword.constants.SUBSCRIPTION_TYPE_GOOGLE
import app.yoword.constants.SUBSCRIPTION_TYPE_LIST
import app.yoword.constants.TESTER_EMAILS
import app.yoword.managers.log.LogManager.printLog
import app.yoword.requests.ApiEndpoints.GET_SUBSCRIPTION_STATUS_API_URL
import app.yoword.managers.server_client.ServerClient
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.yoword.managers.rewarded_ads.RewardedAd
import app.yoword.managers.rewarded_ads.loadAd
import app.yoword.managers.rewarded_ads.init
import android.app.Activity

/**

PROCESS

1. Check if Google Play Services are installed on the device
2. Request subscription status from Google Play
3. Request subscription status from the YoWord server
4. Request subscription price from Google Play
5. Applying test data

RESULT

1. Subscription status
2. Subscription type
3. Expiration date
4. Subscription price

*/

private const val LOG_AUTHOR = "GetSubscriptionStatusRequest"

object GetSubscriptionStatusRequest {

    // Response model
    data class SubscriptionStatusResponseModel(
        val requestSuccess: Boolean,
        val subscriptionStatus: Boolean,
        val subscriptionType: String?,
        val subscriptionState: String?,
        val subscriptionProduct: String?,
        val subscriptionGoogleAccountConflict: Boolean,
        val subscriptionExpiration: String?,
        val subscriptionProvider: String?,
        val subscriptionPrice: String?,
        val karmaScore: Int,
        val karmaReward: Int,
        val adEnabled: Boolean
    )

    // Server request model
    @Serializable
    data class ServerSubscriptionStatusRequestModel(
        @SerialName("user_id") val userId: String,
        @SerialName("token") val token: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("purchase_token") val purchaseToken: String = "",
        @SerialName("purchase_state") val purchaseState: Int = 0,
        @SerialName("today") val today: String,
        @SerialName("force_check") val forceCheck: Boolean
    )

    // Server response model
    @Serializable
    data class ServerSubscriptionStatusResponseModel(

        // Subscription parameters
        @SerialName("status") val status: Boolean,
        @SerialName("type") val type: String? = null,
        @SerialName("state") val state: String? = null,
        @SerialName("product") val product: String? = null,
        @SerialName("google_account_conflict") val googleAccountConflict: Boolean? = false,
        @SerialName("expiration") val expiration: String? = null,

        // Ad parameters
        @SerialName("ad_enabled") val adEnabled: Boolean? = false,
        @SerialName("ad_provider") val adProvider: String? = null,
        @SerialName("karma_reward") val karmaReward: Int? = null,
        @SerialName("google_ad_mob_id") val googleAdMobId: String? = null,
        @SerialName("levelplay_app_id") val levelPlayAppId: String? = null,
        @SerialName("levelplay_ad_unit_id") val levelPlayAdUnitId: String? = null
    )

    suspend fun run(
        activity: Activity,
        app: YoWordApplication,
        weHaveToCheckThePrice: Boolean = false,
        weHaveToCheckGoogleApi: Boolean = false,
        weHaveToPrepareRewardedVideo: Boolean = false

    ) : SubscriptionStatusResponseModel {

        // Initialize dependencies
        val billingClient = app.billingClient
        val context = app.applicationContext
        val user = app.user.get()
        val userId = user?.id ?: error("User ID not found")
        val token = user.token
        var karmaScore = user.karma

        var responseAsJson: ServerSubscriptionStatusResponseModel? = null

        // Preparing key variables
        var purchaseTokenFromGoogle: String? = null
        var purchaseStateFromGoogle: Int? = null
        var weHaveConnectedToServer = false
        var subscriptionStatusFromServer = false
        var subscriptionTypeFromServer: String? = null
        var subscriptionStateFromServer: String? = null
        var subscriptionProductFromServer: String? = null
        var subscriptionGoogleAccountConflictFromServer = false
        var subscriptionExpirationFromServer: String? = null
        var subscriptionProviderFromGoogle: String? = null
        var subscriptionPriceFromGoogle: String? = null

        // Variables for server ad parameters
        var adEnabledFromServer = false
        var adProviderFromServer: String? = null
        var karmaRewardFromServer: Int = 0
        var googleAdMobIdFromServer: String? = null
        var levelPlayAppIdFromServer: String? = null
        var levelPlayAdUnitIdFromServer: String? = null

        // Get the current device date in "2026-07-02" format
        val todayAsStroke = java.time.LocalDate.now().toString()

        // BLOCK 1: Check if Google Play Services are installed on the device
        val weHaveGooglePlay: Boolean = (
                GoogleApiAvailability
                    .getInstance()
                    .isGooglePlayServicesAvailable(context)
                        == ConnectionResult.SUCCESS
                )

        val weHaveBillingClient = billingClient.isReady

        printLog(
            LOG_AUTHOR, (
                    "weHaveGooglePlay = $weHaveGooglePlay, " +
                            "weHaveBillingClient = $weHaveBillingClient"
                    )
        )

        // BLOCK 2: Check via Google Play if the user has an active subscription

        // If Google Play Services is installed
        if (weHaveGooglePlay && weHaveBillingClient) {
            try {

                // Send request to Google Play
                val googlePlayResponse = (
                        billingClient
                            // Only active subscriptions that have not yet expired
                            .queryPurchasesAsync(
                                QueryPurchasesParams
                                    .newBuilder()
                                    .setProductType(BillingClient.ProductType.SUBS)
                                    .build()
                            )
                        )

                // Google Play purchase token
                purchaseTokenFromGoogle = (
                        googlePlayResponse
                            .purchasesList
                            .firstOrNull()
                            ?.purchaseToken
                        )

                // Google Play purchase status
                purchaseStateFromGoogle = (
                        googlePlayResponse
                            .purchasesList
                            .firstOrNull()
                            ?.purchaseState
                        )

                printLog(
                    LOG_AUTHOR, (
                            "Google Play Subscription Status Request: " +
                                    "purchaseTokenFromGoogle = $purchaseTokenFromGoogle, " +
                                    "purchaseStateFromGoogle = $purchaseStateFromGoogle"
                            )
                )
            }
            // If an error occurred during the Google Play request
            catch (error: Exception) {
                printLog(
                    LOG_AUTHOR, (
                            "Google Play Subscription Status Request: Failed to retrieve subscription information. " +
                                    "Exception = $error"
                            )
                )

            }
        // If Google Play Services are not installed on the device
        } else {
            printLog(
                LOG_AUTHOR, (
                        "Google Play Subscription Status Request: Subscription check canceled. " +
                                "weHaveGooglePlay = $weHaveGooglePlay, " +
                                "weHaveBillingClient = $weHaveBillingClient"
                        )
            )
        }

        // BLOCK 3: Check subscription status via the server

        try {

            val deviceId = app.deviceIDProvider.get()

            printLog(
                LOG_AUTHOR, (
                        "Server Request Data: " +
                                "userId = $userId, " +
                                "token = $token, " +
                                "deviceId = $deviceId, " +
                                "purchaseToken = ${purchaseTokenFromGoogle ?: ""}, " +
                                "purchaseState = ${purchaseStateFromGoogle ?: 0}, " +
                                "forceCheck = $weHaveToCheckGoogleApi"

                        )
            )

            val response = ServerClient
                .httpClient
                .post(GET_SUBSCRIPTION_STATUS_API_URL) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        ServerSubscriptionStatusRequestModel(
                            userId = userId,
                            token = token,
                            deviceId = deviceId,
                            purchaseToken = purchaseTokenFromGoogle ?: "",
                            purchaseState = purchaseStateFromGoogle ?: 0,
                            today = todayAsStroke,
                            forceCheck = weHaveToCheckGoogleApi
                        )
                    )
                }

            if (response.status.value == 200) {
                // Parse the JSON response
                responseAsJson = response.body<ServerSubscriptionStatusResponseModel>()

                // Read subscription-related fields
                weHaveConnectedToServer = true
                subscriptionStatusFromServer = responseAsJson.status
                subscriptionTypeFromServer = responseAsJson.type
                subscriptionStateFromServer = responseAsJson.state
                subscriptionProductFromServer = responseAsJson.product
                subscriptionGoogleAccountConflictFromServer =
                    responseAsJson.googleAccountConflict ?: false
                subscriptionExpirationFromServer = responseAsJson.expiration

                // Save the subscription status to the database
                app.user.update(
                    subscriptionStatus = subscriptionStatusFromServer
                )

                printLog(
                    LOG_AUTHOR, (
                            "Server Request Result: " +
                                    "weHaveConnectedToServer = ${true}, " +
                                    "subscriptionStatusFromServer = $subscriptionStatusFromServer, " +
                                    "subscriptionTypeFromServer = $subscriptionTypeFromServer, " +
                                    "subscriptionStateFromServer = $subscriptionStateFromServer, " +
                                    "subscriptionProductFromServer = $subscriptionProductFromServer, " +
                                    "subscriptionGoogleAccountConflictFromServer = $subscriptionGoogleAccountConflictFromServer, " +
                                    "subscriptionExpirationFromServer = $subscriptionExpirationFromServer"
                            )
                )
            } else {
                printLog(LOG_AUTHOR, "Server returned an error: ${response.status}", "error")
            }
        } catch (e: Exception) {
            printLog(LOG_AUTHOR, "Network error during the server request: $e", "error")
        }

        // BLOCK 4: Loading the rewarded video

        // Read ad-related fields
        adEnabledFromServer = responseAsJson?.adEnabled ?: false
        adProviderFromServer = responseAsJson?.adProvider
        karmaRewardFromServer = responseAsJson?.karmaReward ?: 0
        googleAdMobIdFromServer = responseAsJson?.googleAdMobId
        levelPlayAppIdFromServer = responseAsJson?.levelPlayAppId
        levelPlayAdUnitIdFromServer = responseAsJson?.levelPlayAdUnitId

        if (!subscriptionStatusFromServer
            && adEnabledFromServer ) {

            printLog(
                LOG_AUTHOR,
                message = "Ads enabled")

            // Updating ad network data
            RewardedAd.init(
                activity = activity,
                serverAdProvider = adProviderFromServer,
                serverGoogleAdMobId = googleAdMobIdFromServer,
                serverLevelPlayAppId = levelPlayAppIdFromServer,
                serverLevelPlayAdUnitId = levelPlayAdUnitIdFromServer
            )

            // Send the request to load the video and...
            // ...and do not wait for the loading to finish
            if (
                // If the user navigated to the "karma" screen
                // and their karma is less than MAX_KARMA_FOR_ADS
                (weHaveToPrepareRewardedVideo && karmaScore <= MAX_KARMA_FOR_ADS)
                // Or if the user's karma is 0
                || karmaScore == 0){

                // Send the request to load the video
                RewardedAd.loadAd(context)
            }

        } else {
            printLog(
                LOG_AUTHOR,
                message = "Ads disabled"
            )
        }
        
        // BLOCK 5: Retrieve the subscription price via Google Play

        // If Google Play Services is installed
        if (
            weHaveToCheckThePrice
            && weHaveGooglePlay
            && weHaveBillingClient
            && !subscriptionStatusFromServer
        ) {
            try {

                // Send a request to Google Play
                val googlePlayResponse = (
                        billingClient
                            .queryProductDetails(
                                QueryProductDetailsParams
                                    .newBuilder()
                                    .setProductList(
                                        listOf(
                                            QueryProductDetailsParams
                                                .Product
                                                .newBuilder()
                                                .setProductId(BASE_GOOGLE_PLAY_PRODUCT_ID)
                                                .setProductType(BillingClient.ProductType.SUBS)
                                                .build()
                                        )
                                    )
                                    .build()
                            )
                        )

                // Extracting the response status
                val googlePlayResponseCode = (
                        googlePlayResponse
                            .billingResult
                            .responseCode
                        )

                // Extracting the base offer (its offerId is either null or empty)
                val basePlanOffer = googlePlayResponse.productDetailsList
                    ?.firstOrNull()
                    ?.subscriptionOfferDetails
                    ?.firstOrNull { it.offerId.isNullOrEmpty() }
                // If the base offer was not found, take the first one
                    ?: googlePlayResponse.productDetailsList?.firstOrNull()?.subscriptionOfferDetails?.firstOrNull()

                // Base product price on Google Play
                subscriptionPriceFromGoogle = (
                        basePlanOffer
                            ?.pricingPhases
                            ?.pricingPhaseList
                            ?.lastOrNull()
                            ?.formattedPrice)

                subscriptionProviderFromGoogle = "google"

                printLog(
                    LOG_AUTHOR,
                    message = ("Google Play Price Request: " +
                               "googlePlayResponseCode = $googlePlayResponseCode, " +
                               "subscriptionPriceFromGoogle = $subscriptionPriceFromGoogle, " +
                               "subscriptionProviderFromGoogle = $subscriptionProviderFromGoogle")
                )
            }

            // If an error occurred during the request
            catch (error: Exception) {
                printLog(
                    LOG_AUTHOR,
                    message = ("Google Play Price Request: Не удалось получить информацию о продукте. " +
                               "Exception = $error")
                )
            }

        // If the price request was canceled
        } else {
            printLog(
                LOG_AUTHOR,
                message = ("Google Play Price Request: Проверка цены отменена. " +
                           "weHaveToCheckThePrice = $weHaveToCheckThePrice, " +
                           "weHaveGooglePlay = $weHaveGooglePlay, " +
                           "weHaveBillingClient = $weHaveBillingClient, " +
                           "subscriptionStatusFromServer = $subscriptionStatusFromServer")
            )
        }

        // BLOCK 5: Applying test data
        
        if (user.email in TESTER_EMAILS
            && BuildConfig.DEBUG){

            // [1] SPECIAL LIST
          
            // User's email is in the special list
            if (user.name == "karma_test_1"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_LIST
                subscriptionProviderFromGoogle = SUBSCRIPTION_TYPE_LIST
            }
            // User's email is in the special list + date
            else if (user.name == "karma_test_2"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_LIST
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_TYPE_LIST
            }
            
            // [2] USER HAS A GOOGLE SUBSCRIPTION

            // Has a Google subscription, Grace Period
            else if (user.name == "karma_test_3"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_GRACE_PERIOD
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // Has a Google subscription, Grace Period, account conflict
            else if (user.name == "karma_test_4"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_GRACE_PERIOD
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // Has a Google subscription, Active
            else if (user.name == "karma_test_5"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_ACTIVE
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // Has a Google subscription, Active, conflict
            else if (user.name == "karma_test_6"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_ACTIVE
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // Has a Google subscription, Canceled
            else if (user.name == "karma_test_7"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_CANCELED
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // Has a Google subscription, Canceled, conflict
            else if (user.name == "karma_test_8"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = true
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_CANCELED
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }

            // [3] GOOGLE PLAY SUBSCRIPTION IS ON HOLD

            // No Google subscription, Pending
            else if (user.name == "karma_test_9"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_PENDING
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // No Google subscription, Pending, conflict
            else if (user.name == "karma_test_10"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_PENDING
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // No Google subscription, Paused
            else if (user.name == "karma_test_11"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_PAUSED
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // No Google subscription, Paused, conflict
            else if (user.name == "karma_test_12"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_PAUSED
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // No Google subscription, Repair
            else if (user.name == "karma_test_13"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_REPAIR
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // No Google subscription, Repair, conflict
            else if (user.name == "karma_test_14"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionTypeFromServer = SUBSCRIPTION_TYPE_GOOGLE
                subscriptionStateFromServer = SUBSCRIPTION_STATE_REPAIR
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionExpirationFromServer = "2110-10-10"
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }

            // [4] No Google subscription, has Google Play

            // No Google subscription, Repair
            else if (user.name == "karma_test_15"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = false
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }
            // No Google subscription, Repair, conflict
            else if (user.name == "karma_test_16"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionProductFromServer = BASE_GOOGLE_PLAY_PRODUCT_ID
                subscriptionGoogleAccountConflictFromServer = true
                subscriptionProviderFromGoogle = SUBSCRIPTION_PROVIDER_GOOGLE
                subscriptionPriceFromGoogle = "1 000 000 USD"
            }

            // [5] No subscription, no Google Play
            else if (user.name == "karma_test_17"){

                weHaveConnectedToServer = true
                subscriptionStatusFromServer = false
                subscriptionPriceFromGoogle = null
                subscriptionProviderFromGoogle = null
            }

            // [6] Changing the karma amount

            else if (user.name?.startsWith("karma_") == true &&
                user.name.removePrefix("karma_").toIntOrNull() != null) {
                // Executes if a valid number follows "karma_"

                val newKarma: Int = user.name?.removePrefix("karma_")?.toIntOrNull() ?: 0
                app.user.update(karma = newKarma, name = "")
                karmaScore = newKarma

                UpdateKarmaRequest.run(app)

            }

            // Updating the subscription according to the test data
            app.user.update(
                subscriptionStatus = subscriptionStatusFromServer
            )
        }

        // BLOCK 7: Final result
        printLog(LOG_AUTHOR, (
                "Результат: " +
                "serverConnectionSuccess = $weHaveConnectedToServer, " +
                "subscriptionStatus = $subscriptionStatusFromServer, " +
                "subscriptionType = $subscriptionTypeFromServer, " +
                "subscriptionState = $subscriptionStateFromServer, " +
                "subscriptionProduct = $subscriptionProductFromServer, " +
                "subscriptionGoogleAccountConflict = $subscriptionGoogleAccountConflictFromServer, " +
                "subscriptionExpiration = $subscriptionExpirationFromServer, " +
                "subscriptionProvider = $subscriptionProviderFromGoogle, " +
                "subscriptionPrice = $subscriptionPriceFromGoogle, " +
                "karmaScore = $karmaScore, " +
                "karmaRewardList = $karmaRewardFromServer, " +
                "adEnabled = $adEnabledFromServer"
                )
        )

        return SubscriptionStatusResponseModel(
            requestSuccess = weHaveConnectedToServer,
            subscriptionStatus = subscriptionStatusFromServer,
            subscriptionType = subscriptionTypeFromServer,
            subscriptionState = subscriptionStateFromServer,
            subscriptionProduct = subscriptionProductFromServer,
            subscriptionGoogleAccountConflict = subscriptionGoogleAccountConflictFromServer,
            subscriptionExpiration = subscriptionExpirationFromServer,
            subscriptionProvider = subscriptionProviderFromGoogle,
            subscriptionPrice = subscriptionPriceFromGoogle,
            karmaScore = karmaScore,
            karmaReward = karmaRewardFromServer,
            adEnabled = adEnabledFromServer
        )
    }
}
