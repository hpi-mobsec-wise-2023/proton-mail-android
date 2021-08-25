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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.birbit.android.jobqueue.Params;

import java.util.List;

import ch.protonmail.android.api.models.IDList;
import ch.protonmail.android.core.Constants;
import ch.protonmail.android.data.local.CounterDao;
import ch.protonmail.android.data.local.CounterDatabase;
import ch.protonmail.android.data.local.model.Message;
import ch.protonmail.android.data.local.model.UnreadLabelCounter;

@Deprecated // Replaced with ApplyLabelWorker
public class ApplyLabelJob extends ProtonMailEndlessJob {
    private List<String> messageIds;
    private String labelId;

    public ApplyLabelJob(List<String> messageIds, String labelId) {
        super(new Params(Priority.MEDIUM).requireNetwork().persist().groupBy(Constants.JOB_GROUP_LABEL));
        this.messageIds = messageIds;
        this.labelId = labelId;
    }

    @Override
    public void onAdded() {
        countUnread(ModificationMethod.INCREMENT);
    }

    @Override
    protected void onProtonCancel(int cancelReason, @Nullable Throwable throwable) {
        countUnread(ModificationMethod.DECREMENT);
    }

    enum ModificationMethod {
        INCREMENT, DECREMENT
    }

    private void countUnread(@NonNull ModificationMethod modificationMethod) {
        final CounterDao counterDao = CounterDatabase.Companion
                .getInstance(getApplicationContext(), getUserId())
                .getDao();
        int totalUnread = 0;
        for (String messageId : messageIds) {
            Message message = getMessageDetailsRepository().findMessageByIdBlocking(messageId);
            if (message == null) {
                continue;
            }
            if (message.isRead()) {
                totalUnread++;
            }
        }

        UnreadLabelCounter unreadLabelCounter = counterDao.findUnreadLabelById(labelId);
        if (unreadLabelCounter != null) {
            switch (modificationMethod) {
                case INCREMENT:
                    unreadLabelCounter.increment(totalUnread);
                    break;
                case DECREMENT:
                    unreadLabelCounter.decrement(totalUnread);
                    break;
            }
            counterDao.insertUnreadLabel(unreadLabelCounter);
        }
    }

    @Override
    public void onRun() throws Throwable {
        IDList idList = new IDList(labelId, messageIds);
        getApi().labelMessages(idList);
    }
}
