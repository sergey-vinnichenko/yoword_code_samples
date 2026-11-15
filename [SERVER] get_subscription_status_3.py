"""
File: endpoints/private/get_subscription_status_3.py

Answer options
"status": True/False
"type": list, google, family
"state": pending, grace_period, active, paused, repair, canceled
"product": subscription_monthly
"conflict": True
"expiration": 2025-01-07


"""

import json
import traceback

from datetime import datetime, date, timezone

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session
from sqlalchemy import or_
from sqlalchemy.exc import SQLAlchemyError
from importlib.resources import files

from google.oauth2 import service_account
from googleapiclient.discovery import build

from enum import Enum

from database import get_db
from models import User
from models.google_subscription import GoogleSubscription
from utils.verify_userid_and_token import verify_userid_and_token
from subscription.get_google_play_subscription_state import get_google_play_subscription_state


router = APIRouter()

# Константы
PACKAGE_NAME = "app.yoword"
GOOGLE_API_KEY_PATH = "keys/google_cloud_key_8e75a.json"
SCOPES = ["https://www.googleapis.com/auth/androidpublisher"]
SUBSCRIPTION_RECORD_AUTHOR = "endpoint"
DATE_FORMAT = "%Y-%m-%d"
DATABASE_RECORD_AUTHOR = "endpoint, get_status_3"
SUBSCRIPTION_LIST_PATH = (files("resources")
                          .joinpath("subscribers")
                          .joinpath("subscription_list.json"))

# Google Play subscription statuses
class SubscriptionState(Enum):
    UNSPECIFIED  = (0, "unspecified")  # Equivalent to the "No subscription" scenario
    PENDING      = (1, "pending")      # "Payment processing", do not show buttons
    ACTIVE       = (2, "active")       # "You are subscribed", "Change subscription" button
    PAUSED       = (3, "paused")       # "Subscription on hold", "Change subscription" button
    GRACE_PERIOD = (4, "grace_period") # "You are subscribed", "Change subscription" button
    REPAIR       = (5, "repair")       # "Payment failed", "Change subscription" button
    CANCELED     = (6, "canceled")     # "You are subscribed", "Change subscription" button
    EXPIRED      = (7, "expired")      # Equivalent to the "No subscription" scenario

    def __new__(cls, code: int, text: str):
        obj = object.__new__(cls)
        obj.code = code
        obj.text = text
        return obj

# Subscription types
class SubscriptionType:
    LIST = "list"
    GOOGLE = "google"
    FAMILY = "family"

# ANSWER FIELDS
class Return:
    STATUS = "status"
    TYPE = "type"
    STATE = "state"
    PRODUCT = "product"
    GOOGLE_ACCOUNT_CONFLICT = "google_account_conflict"
    EXPIRATION = "expiration"
    ERROR = "error"

# Incoming request model
class RequestModel(BaseModel):
    user_id: str
    token: str
    purchase_token: str | None = None
    purchase_state: int | None = None
    today: date | None = None # 2025-01-07
    force_check: bool | None = None

# Connect Google API client
def connect_to_google_api():
    credentials = service_account.Credentials.from_service_account_file(
        GOOGLE_API_KEY_PATH,
        scopes = SCOPES
    )
    return build(
        serviceName = "androidpublisher",
        version = "v3",
        credentials = credentials)

@router.post("/subscription/get_status_3")
def get_subscription_status(
        request_data: RequestModel,
        data_base: Session = Depends(get_db)
):
    try:

        # [!] Step 1. Extract source data from the request

        user_id = request_data.user_id
        token = request_data.token
        google_play_purchase_token = request_data.purchase_token or ""
        google_play_purchase_state = request_data.purchase_state or 0
        force_check = request_data.force_check or False

        # [!] Step 2. Verify user_id and security token match
        result = verify_userid_and_token(data_base, user_id, token)
        if result is not True:
            return JSONResponse(
                status_code = 403,
                content = {
                    Return.ERROR: result
                }
            )

        # [!] Step 3. Find user in the database
        user = data_base.query(User).filter(User.id == user_id).first()
        if not user:
            return JSONResponse(
                status_code = 403,
                content = {
                    Return.ERROR: "invalid_userid"
                }
            )

        user_email = user.email

        # [!] Step 4. DETERMINE CURRENT DATE

        # If the date sent by the user                             .
        # is within +/- 1 day of the server date – take it,  .
        # otherwise take the server date.                                 .
        # This allows calculating subscription information      .
        # according to the user's timezone.

        server_today = date.today()
        request_today = request_data.today

        if (request_today
            and -1 <= (request_today - server_today).days <= 1):
            user_timezone_today = request_today
        else:
            user_timezone_today = server_today

        # [!] Step 5. CHECK EMAIL LIST
      
        # Read the subscriber list file
        try:
            subscription_list = (
                json.loads(SUBSCRIPTION_LIST_PATH.read_text(encoding="utf-8"))
            )
        except (json.JSONDecodeError, OSError, AttributeError):
            subscription_list = []

        # Iterate over entries in the list
        for item in subscription_list:

            # If an entry with the user's email is found
            if item.get("email") == user_email:

                # If there is an expiration date
                try:
                    expiry_date_stroke = item.get("expiry_date")
                    expiry_date = (datetime
                                   .strptime(expiry_date_stroke, DATE_FORMAT)
                                   .date()
                                  )

                    # If the expiration date has not yet passed
                    if expiry_date >= user_timezone_today:

                        # Save to the database
                        user.subscription_status = True
                        data_base.commit()

                        return {
                            Return.STATUS: True,
                            Return.TYPE: SubscriptionType.LIST,
                            Return.EXPIRATION: expiry_date_stroke
                        }

                    # If the expiration date has passed
                    else:

                        # Exit the loop
                        break

                # If there is no expiration date
                except (ValueError, TypeError):

                    # Save to the database
                    user.subscription_status = True
                    data_base.commit()

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.LIST
                    }

        # [!] Step 6. CHECK GOOGLE SUBSCRIPTION

        subscription_record_by_user_id = (
            data_base.query(GoogleSubscription)
                .filter(GoogleSubscription.user_id == user_id)
                .order_by(GoogleSubscription.expiry_time.desc())
                .first())

        # [!] GOOGLE PLAY - has subscription or SERVER - has subscription
        # If there is no subscription, all these checks are skipped
        if (subscription_record_by_user_id
            or google_play_purchase_token):

            google_play_reported_the_payment = bool(
                google_play_purchase_state == 1)

            server_subscription_is_active = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.ACTIVE.code)

            subscription_is_expired_by_date = bool(
                subscription_record_by_user_id
                and subscription_record_by_user_id.expiry_time.date()
                < user_timezone_today)

            last_check_days_ago = ((
                datetime.now(timezone.utc)
                - subscription_record_by_user_id.updated_at
                    ).total_seconds() / 86400
                    if subscription_record_by_user_id
                    else 0)

            days_to_expiration = ((
                subscription_record_by_user_id.expiry_time.date()
                - user_timezone_today
                ).days
                if subscription_record_by_user_id
                else 99)


            it_is_time_to_check_subscription_state = bool(
                subscription_record_by_user_id
                and
                (
                    # 3 days left until the subscription ends
                    # Do not check more often than once a day
                    (0 <= days_to_expiration <= 3
                    and last_check_days_ago > 1.0)

                    or

                    # If more than 7 days have passed since the last update
                    (server_subscription_is_active
                     and last_check_days_ago > 7.0)
                )
            )

            # [!] If GOOGLE PLAY reported a payment
            # If there is no subscription, all these checks are skipped
            # Get new variable value
            if (
                    (google_play_reported_the_payment
                    and not server_subscription_is_active)
                or
                    (server_subscription_is_active
                     and  subscription_is_expired_by_date)
                or
                    it_is_time_to_check_subscription_state
                or
                    force_check
            ):

                print("[DB] Request to Google API")

                # Check via Google API
                get_google_play_subscription_state(
                    google_play_purchase_token,
                    DATABASE_RECORD_AUTHOR,
                    data_base,
                    user_id
                )

                subscription_record_by_user_id = (
                    data_base.query(GoogleSubscription)
                    .filter(GoogleSubscription.user_id == user_id)
                    .order_by(GoogleSubscription.expiry_time.desc())
                    .first())

                server_subscription_is_active = (
                    subscription_record_by_user_id
                    and subscription_record_by_user_id.subscription_state
                    == SubscriptionState.ACTIVE.code)

                subscription_is_expired_by_date = bool(
                    subscription_record_by_user_id
                    and subscription_record_by_user_id.expiry_time.date()
                    < user_timezone_today)

                pass

            # [!] Gather data

            subscription_record_by_google_play_purchase_token = (
                data_base.query(GoogleSubscription)
                .filter(GoogleSubscription.purchase_token == google_play_purchase_token)
                .first() if google_play_purchase_token else None)

            expiration_date_as_stroke = (
                subscription_record_by_user_id.expiry_time.date().strftime(DATE_FORMAT)
                if subscription_record_by_user_id and subscription_record_by_user_id.expiry_time
                else None
            )

            server_subscription_is_expired = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.EXPIRED.code)

            google_play_token_has_conflict_with_user_id = bool(
                subscription_record_by_google_play_purchase_token
                and subscription_record_by_google_play_purchase_token.user_id != user_id)

            server_subscription_is_unspecified = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.UNSPECIFIED.code)

            server_subscription_is_pending = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.PENDING.code)

            server_subscription_is_paused = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.PAUSED.code)

            server_subscription_is_on_grace_period = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.GRACE_PERIOD.code)

            server_subscription_is_repair = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.REPAIR.code)

            server_subscription_is_canceled = (
                subscription_record_by_user_id
                and subscription_record_by_user_id.subscription_state
                == SubscriptionState.CANCELED.code)

            user_has_no_active_subscription = bool(
                server_subscription_is_unspecified
                or server_subscription_is_expired
                or not subscription_record_by_user_id
                or (subscription_is_expired_by_date
                    and (server_subscription_is_active
                        or server_subscription_is_canceled)))

            server_subscription_product_id = (
                subscription_record_by_user_id.product_id
                if subscription_record_by_user_id else None)

            print(f"[DB] Есть запись в базе данных =  {bool(subscription_record_by_user_id)}")
            if subscription_record_by_user_id:
                print(f"[DB] subscription_record_by_user_id.subscription_state = {subscription_record_by_user_id.subscription_state}")
            print(f"[DB] user_has_no_active_subscription = {user_has_no_active_subscription}")
            print(f"[DB] server_subscription_is_active = {server_subscription_is_active}")

            # NO SUBSCRIPTION

            if user_has_no_active_subscription:

                if google_play_token_has_conflict_with_user_id:

                    user.subscription_status = False
                    data_base.commit()

                    return {
                        Return.STATUS: False,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    pass

            # PENDING
            elif server_subscription_is_pending:

                user.subscription_status = False
                data_base.commit()

                if (google_play_token_has_conflict_with_user_id
                    or not google_play_purchase_token):

                    return {
                        Return.STATUS: False,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.PENDING.text,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    return {
                        Return.STATUS: False,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.PENDING.text
                    }

            # GRACE_PERIOD
            elif server_subscription_is_on_grace_period:

                user.subscription_status = True
                data_base.commit()

                if (google_play_token_has_conflict_with_user_id
                        or not google_play_purchase_token):

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.GRACE_PERIOD.text,
                        Return.PRODUCT: server_subscription_product_id,
                        Return.EXPIRATION: expiration_date_as_stroke,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.GRACE_PERIOD.text,
                        Return.PRODUCT: server_subscription_product_id,
                        Return.EXPIRATION: expiration_date_as_stroke
                    }

            # ACTIVE
            elif server_subscription_is_active:

                user.subscription_status = True
                data_base.commit()

                if (google_play_token_has_conflict_with_user_id
                    or not google_play_purchase_token):

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.ACTIVE.text,
                        Return.PRODUCT: server_subscription_product_id,
                        Return.EXPIRATION: expiration_date_as_stroke,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.ACTIVE.text,
                        Return.PRODUCT: server_subscription_product_id,
                        Return.EXPIRATION: expiration_date_as_stroke
                    }

            # PAUSED
            elif server_subscription_is_paused:

                user.subscription_status = False
                data_base.commit()

                if google_play_token_has_conflict_with_user_id:

                    return {
                        Return.STATUS: False,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.PAUSED.text,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    return {
                        Return.STATUS: False,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.PAUSED.text,
                    }

            # REPAIR
            elif server_subscription_is_repair:

                user.subscription_status = False
                data_base.commit()

                if google_play_token_has_conflict_with_user_id:

                    return {
                        Return.STATUS: False,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.REPAIR.text,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    return {
                        Return.STATUS: False,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.REPAIR.text,
                    }

            # CANCELED
            elif server_subscription_is_canceled:

                user.subscription_status = True
                data_base.commit()

                if (google_play_token_has_conflict_with_user_id
                    or not google_play_purchase_token):

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.CANCELED.text,
                        Return.PRODUCT: server_subscription_product_id,
                        Return.EXPIRATION: expiration_date_as_stroke,
                        Return.GOOGLE_ACCOUNT_CONFLICT: True
                    }

                else:

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.GOOGLE,
                        Return.STATE: SubscriptionState.CANCELED.text,
                        Return.PRODUCT: server_subscription_product_id,
                        Return.EXPIRATION: expiration_date_as_stroke
                    }

        # [!] Step 7. CHECK FAMILY SUBSCRIPTIONS>

        if user_email:

            # Search if user's email is in "family_email_X" fields
            database_record = data_base.query(GoogleSubscription).filter(
                or_(
                    GoogleSubscription.family_email_1 == user_email,
                    GoogleSubscription.family_email_2 == user_email,
                    GoogleSubscription.family_email_3 == user_email,
                    GoogleSubscription.family_email_4 == user_email,
                    GoogleSubscription.family_email_5 == user_email
                )
                # Sort by "expiry_time", take the first one
            ).order_by(GoogleSubscription.expiry_time.desc()).first()

            # If record is found
            if database_record:

                expiration_date = database_record.expiry_time.date()

                if (expiration_date> user_timezone_today
                    and database_record.subscription_status is True):

                    # Update "subscription_status" field in the "user" table
                    user.subscription_status = True
                    data_base.commit()

                    return {
                        Return.STATUS: True,
                        Return.TYPE: SubscriptionType.FAMILY,
                        Return.EXPIRATION: expiration_date.strftime(DATE_FORMAT)
                    }

        # [!] Step 8. If subscription is not found

        # Update "subscription_status" field in the "user" table
        user.subscription_status = False
        data_base.commit()

        # Send response
        return {
            Return.STATUS: False
        }

    # [!] Step 9. Database error
    except SQLAlchemyError:
        try:
            data_base.rollback()
        except Exception:
            pass

        # Send response
        return JSONResponse(
            status_code = 500,
            content = {
                Return.ERROR: "database_error"
            }
        )

    # [!] Step 9. Any unknown error
    except Exception as error:
        try:
            data_base.rollback()
        except Exception:
            pass

        # Send response
        return JSONResponse(
            status_code = 500,
            content = {
                Return.ERROR: f"Unknown error: {str(error)} | Traceback: {traceback.format_exc()}"

            }
        )
