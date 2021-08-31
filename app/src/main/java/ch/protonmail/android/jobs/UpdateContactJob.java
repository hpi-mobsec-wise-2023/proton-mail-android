/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.jobs;

import static ch.protonmail.android.api.segments.BaseApiKt.RESPONSE_CODE_ERROR_EMAIL_DUPLICATE_FAILED;
import static ch.protonmail.android.api.segments.BaseApiKt.RESPONSE_CODE_ERROR_EMAIL_EXIST;
import static ch.protonmail.android.api.segments.BaseApiKt.RESPONSE_CODE_ERROR_INVALID_EMAIL;

import android.database.sqlite.SQLiteBlobTooBigException;

import androidx.annotation.NonNull;

import com.birbit.android.jobqueue.Params;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ch.protonmail.android.api.models.ContactEncryptedData;
import ch.protonmail.android.api.models.CreateContactV2BodyItem;
import ch.protonmail.android.api.models.contacts.send.LabelContactsBody;
import ch.protonmail.android.api.rx.ThreadSchedulers;
import ch.protonmail.android.contacts.details.presentation.model.ContactLabelUiModel;
import ch.protonmail.android.contacts.groups.jobs.SetMembersForContactGroupJob;
import ch.protonmail.android.core.Constants;
import ch.protonmail.android.crypto.CipherText;
import ch.protonmail.android.crypto.Crypto;
import ch.protonmail.android.crypto.UserCrypto;
import ch.protonmail.android.data.local.ContactDao;
import ch.protonmail.android.data.local.ContactDatabase;
import ch.protonmail.android.data.local.model.ContactData;
import ch.protonmail.android.data.local.model.ContactEmail;
import ch.protonmail.android.data.local.model.FullContactDetails;
import ch.protonmail.android.data.local.model.FullContactDetailsResponse;
import ch.protonmail.android.events.ContactEvent;
import ch.protonmail.android.labels.data.LabelRepository;
import ch.protonmail.android.utils.AppUtil;
import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.property.Email;
import timber.log.Timber;

public class UpdateContactJob extends ProtonMailEndlessJob {

    private final String mContactId;
    private final String mContactName;
    private final List<ContactEmail> mContactEmails;
    private final String mEncryptedData;
    private final String mSignedData;
    private final Map<ContactEmail, List<ContactLabelUiModel>> mMapEmailGroupsIds;
    private final LabelRepository labelRepository;

    private transient ContactDao mContactDao;

    public UpdateContactJob(
            String contactId,
            @NonNull String contactName,
            @NonNull List<ContactEmail> contactEmails,
            String encryptedData,
            String signedData,
            Map<ContactEmail, List<ContactLabelUiModel>> mapEmailGroupsIds,
            LabelRepository labelRepository
    ) {
        super(new Params(Priority.MEDIUM).requireNetwork().persist().groupBy(Constants.JOB_GROUP_CONTACT));
        mContactId = contactId;
        mContactName = contactName;
        mContactEmails = contactEmails;
        mEncryptedData = encryptedData;
        mSignedData = signedData;
        mMapEmailGroupsIds = mapEmailGroupsIds;
        this.labelRepository = labelRepository;
    }

    @Override
    public void onAdded() {
        UserCrypto crypto = Crypto.forUser(getUserManager(), getUserId());
        try {
            CipherText tct = crypto.encrypt(mEncryptedData, false);
            String encryptedDataSignature = crypto.sign(mEncryptedData);
            String signedDataSignature = crypto.sign(mSignedData);

            updateContact(mContactName, mContactEmails, tct.getArmored(), encryptedDataSignature, signedDataSignature, false);
            AppUtil.postEventOnUi(new ContactEvent(ContactEvent.SAVED, true));
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!getQueueNetworkUtil().isConnected()) {
            AppUtil.postEventOnUi(new ContactEvent(ContactEvent.NO_NETWORK, true));
        }
    }

    @Override
    public void onRun() throws Throwable {
        UserCrypto crypto = Crypto.forUser(getUserManager(), getUserId());
        requireContactDao();

        CipherText tct = crypto.encrypt(mEncryptedData, false);
        String encryptedDataSignature = crypto.sign(mEncryptedData);
        String signedDataSignature = crypto.sign(mSignedData);

        CreateContactV2BodyItem body = new CreateContactV2BodyItem(mSignedData, signedDataSignature,
                tct.getArmored(), encryptedDataSignature);
        FullContactDetailsResponse response = getApi().updateContact(mContactId, body);

        if (response != null) {
            Timber.v("Update contacts response code:%s error:%s", response.getCode(), response.getError());
            if (response.getCode() == RESPONSE_CODE_ERROR_EMAIL_EXIST) {
                // TODO: 9/14/17 todoContacts throw error
                AppUtil.postEventOnUi(new ContactEvent(ContactEvent.ALREADY_EXIST, true));
            } else if (response.getCode() == RESPONSE_CODE_ERROR_INVALID_EMAIL) {
                // TODO: 9/14/17 todoContacts throw error
                AppUtil.postEventOnUi(new ContactEvent(ContactEvent.INVALID_EMAIL, true));
            } else if (response.getCode() == RESPONSE_CODE_ERROR_EMAIL_DUPLICATE_FAILED) {
                AppUtil.postEventOnUi(new ContactEvent(ContactEvent.DUPLICATE_EMAIL, true));
            } else {
                updateContact(mContactName, response.getContact().getEmails(), tct.getArmored(), encryptedDataSignature, signedDataSignature, true);
                AppUtil.postEventOnUi(new ContactEvent(ContactEvent.SUCCESS, true));
            }
        }
    }

    private void updateContact(
            @NonNull String contactName,
            @NonNull List<ContactEmail> contactEmails,
            String encryptedData,
            String encryptedDataSignature,
            String signedDataSignature,
            boolean updateJoins
    ) {

        requireContactDao();
        final ContactData contactData = mContactDao.findContactDataById(mContactId);
        if (contactData != null) {
            contactData.setName(contactName);
            mContactDao.saveContactData(contactData);
        }

        List<ContactEmail> emails = mContactDao.findContactEmailsByContactIdBlocking(mContactId);
        mContactDao.deleteAllContactsEmails(emails);

        for (ContactEmail email : contactEmails) {
            final String emailToClear = email.getEmail();
            mContactDao.clearByEmailBlocking(emailToClear);
        }
        mContactDao.saveAllContactsEmailsBlocking(contactEmails);
        Map<ContactLabelUiModel, List<String>> mapContactGroupContactEmails = new HashMap<>();
        if (updateJoins) {
            for (ContactEmail email : contactEmails) {
                List<ContactLabelUiModel> labels = findContactLabelsByEmail(email);
                for (ContactLabelUiModel label : labels) {
                    List<String> labelEmails = mapContactGroupContactEmails.get(label);
                    if (labelEmails == null) {
                        labelEmails = new ArrayList<>();
                    }
                    labelEmails.add(email.getContactEmailId());
                    mapContactGroupContactEmails.put(label, labelEmails);
                }
            }
        }
        FullContactDetails contact = null;
        try {
            contact = mContactDao.findFullContactDetailsByIdBlocking(mContactId);
        } catch (SQLiteBlobTooBigException tooBigException) {
            Timber.i(tooBigException,"Data too big to be fetched");
        }
        if (contact != null) {
            ContactEncryptedData contactEncryptedData = new ContactEncryptedData(encryptedData, encryptedDataSignature, Constants.VCardType.SIGNED_ENCRYPTED);
            ContactEncryptedData contactSignedData = new ContactEncryptedData(mSignedData, signedDataSignature, Constants.VCardType.SIGNED);
            ContactEncryptedData contactEncryptedDataType0 = null;
            List<ContactEncryptedData> contactEncryptedDataList = contact.getEncryptedData();
            for (ContactEncryptedData data : contactEncryptedDataList) {
                if (data.getType() == 0) {
                    contactEncryptedDataType0 = data;
                    break;
                }
            }
            if (contactEncryptedDataType0 != null) {
                String vCardType0String = contactEncryptedDataType0.getData();
                final VCard vCardType0 = vCardType0String != null ? Ezvcard.parse(vCardType0String).first() : null;
                List<Email> emailsType0 = vCardType0.getEmails();
                vCardType0.getEmails().removeAll(emailsType0);
                contact.addEncryptedData(new ContactEncryptedData(vCardType0.write(), "", Constants.VCardType.UNSIGNED));
            }
            contact.addEncryptedData(contactSignedData);
            contact.addEncryptedData(contactEncryptedData);
            contact.setName(contactName);
            contact.setEmails(contactEmails);
            mContactDao.insertFullContactDetailsBlocking(contact);
            if (updateJoins) {
                for (Map.Entry<ContactLabelUiModel, List<String>> entry : mapContactGroupContactEmails.entrySet()) {
                    updateJoins(entry.getKey().getId().getId(), entry.getKey().getName(), entry.getValue());
                }
            } else {
                AppUtil.postEventOnUi(new ContactEvent(ContactEvent.SAVED, true));
            }
        }
    }

    private void updateJoins(String contactGroupId, String contactGroupName, List<String> membersList) {
        LabelContactsBody labelContactsBody = new LabelContactsBody(contactGroupId, membersList);
        try {
            getApi().labelContacts(labelContactsBody)
                    .doOnComplete(() -> {
                        //List<ContactEmailContactLabelJoin> joins = mContactDao.fetchJoinsBlocking(contactGroupId);
                        for (String contactEmail : membersList) {
                           // joins.add(new ContactEmailContactLabelJoin(contactEmail, contactGroupId));
                        }
                        //mContactDao.saveContactEmailContactLabelBlocking(joins);
                    })
                    .doOnError(throwable ->
                            getJobManager().addJobInBackground(
                                    new SetMembersForContactGroupJob(contactGroupId, contactGroupName, membersList, labelRepository)
                            )
                    )
                    .subscribeOn(ThreadSchedulers.io())
                    .observeOn(ThreadSchedulers.io())
                    .subscribe();
        } catch (Exception e) {
            AppUtil.postEventOnUi(new ContactEvent(ContactEvent.ERROR, false));
        }
    }

    private List<ContactLabelUiModel> findContactLabelsByEmail(ContactEmail contactEmail) {
        for (Map.Entry<ContactEmail, List<ContactLabelUiModel>> entry : mMapEmailGroupsIds.entrySet()) {
            if (entry.getKey().getEmail().equals(contactEmail.getEmail())) {
                return entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    private void requireContactDao() {
        if (mContactDao == null) {
            mContactDao = ContactDatabase.Companion
                    .getInstance(getApplicationContext(), getUserId())
                    .getDao();
        }
    }
}
