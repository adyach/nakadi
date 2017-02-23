package org.zalando.nakadi.service.timeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.config.SecuritySettings;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.PartitionStatistics;
import org.zalando.nakadi.domain.Storage;
import org.zalando.nakadi.domain.Timeline;
import org.zalando.nakadi.exceptions.ForbiddenAccessException;
import org.zalando.nakadi.exceptions.NakadiException;
import org.zalando.nakadi.exceptions.TimelineException;
import org.zalando.nakadi.exceptions.TopicRepositoryException;
import org.zalando.nakadi.exceptions.UnableProcessException;
import org.zalando.nakadi.repository.TopicRepository;
import org.zalando.nakadi.repository.TopicRepositoryHolder;
import org.zalando.nakadi.repository.db.EventTypeCache;
import org.zalando.nakadi.repository.db.StorageDbRepository;
import org.zalando.nakadi.repository.db.TimelineDbRepository;
import org.zalando.nakadi.security.Client;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class TimelineService {

    private static final Logger LOG = LoggerFactory.getLogger(TimelineService.class);
    private static final String DEFAULT_STORAGE = "default";

    private final SecuritySettings securitySettings;
    private final EventTypeCache eventTypeCache;
    private final StorageDbRepository storageDbRepository;
    private final TimelineSync timelineSync;
    private final NakadiSettings nakadiSettings;
    private final TimelineDbRepository timelineDbRepository;
    private final TopicRepositoryHolder topicRepositoryHolder;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TimelineService(final SecuritySettings securitySettings,
                           final EventTypeCache eventTypeCache,
                           final StorageDbRepository storageDbRepository,
                           final TimelineSync timelineSync,
                           final NakadiSettings nakadiSettings,
                           final TimelineDbRepository timelineDbRepository,
                           final TopicRepositoryHolder topicRepositoryHolder,
                           final TransactionTemplate transactionTemplate) {
        this.securitySettings = securitySettings;
        this.eventTypeCache = eventTypeCache;
        this.storageDbRepository = storageDbRepository;
        this.timelineSync = timelineSync;
        this.nakadiSettings = nakadiSettings;
        this.timelineDbRepository = timelineDbRepository;
        this.topicRepositoryHolder = topicRepositoryHolder;
        this.transactionTemplate = transactionTemplate;
    }

    public void createTimeline(final String eventTypeName, final String storageId, final Client client)
            throws ForbiddenAccessException, TimelineException, TopicRepositoryException {
        if (!client.getClientId().equals(securitySettings.getAdminClientId())) {
            throw new ForbiddenAccessException("Request is forbidden for user " + client.getClientId());
        }

        try {
            final EventType eventType = eventTypeCache.getEventType(eventTypeName);
            final Storage storage = storageDbRepository.getStorage(storageId)
                    .orElseThrow(() -> new UnableProcessException("No storage with id: " + storageId));
            final Timeline activeTimeline = getTimeline(eventType);
            final TopicRepository currentTopicRepo =
                    topicRepositoryHolder.getTopicRepository(activeTimeline.getStorage());
            final TopicRepository nextTopicRepo = topicRepositoryHolder.getTopicRepository(storage);
            final List<PartitionStatistics> partitionStatistics =
                    currentTopicRepo.loadTopicStatistics(Collections.singleton(activeTimeline.getTopic()));


            final Timeline nextTimeline;
            if (activeTimeline.isFake()) {
                nextTimeline = Timeline.createTimeline(activeTimeline.getEventType(),
                        activeTimeline.getOrder() + 1, storage, activeTimeline.getTopic(), new Date());
            } else {
                final String newTopic = nextTopicRepo.createTopic(partitionStatistics.size(),
                        eventType.getOptions().getRetentionTime());
                nextTimeline = Timeline.createTimeline(activeTimeline.getEventType(),
                        activeTimeline.getOrder() + 1, storage, newTopic, new Date());
            }

            switchTimelines(eventType, activeTimeline, currentTopicRepo, nextTimeline);
        } catch (final NakadiException ne) {
            throw new TimelineException(ne.getMessage(), ne);
        }
    }

    public Timeline getTimeline(final EventType eventType) throws TimelineException {
        try {
            final String eventTypeName = eventType.getName();
            final Optional<Timeline> activeTimeline = eventTypeCache.getActiveTimeline(eventTypeName);
            if (activeTimeline.isPresent()) {
                return activeTimeline.get();
            }

            final Storage storage = storageDbRepository.getStorage(DEFAULT_STORAGE)
                    .orElseThrow(() -> new UnableProcessException("Fake timeline creation failed for event type " +
                            eventType.getName() + ".No default storage defined"));
            return Timeline.createFakeTimeline(eventType, storage);
        } catch (final NakadiException e) {
            LOG.error("Failed to get timeline for event type {}", eventType.getName(), e);
            throw new TimelineException("Failed to get timeline", e);
        }
    }

    public TopicRepository getTopicRepository(final EventType eventType)
            throws TopicRepositoryException, TimelineException {
        final Timeline timeline = getTimeline(eventType);
        return topicRepositoryHolder.getTopicRepository(timeline.getStorage());
    }

    private void switchTimelines(final EventType eventType,
                                 final Timeline activeTimeline,
                                 final TopicRepository currentTopicRepo,
                                 final Timeline nextTimeline) {
        LOG.info("Switching timelines from {} to {}", activeTimeline, nextTimeline);
        transactionTemplate.execute(status -> {
            try {
                timelineDbRepository.createTimeline(nextTimeline);
                timelineSync.startTimelineUpdate(eventType.getName(), nakadiSettings.getTimelineWaitTimeoutMs());
                nextTimeline.setSwitchedAt(new Date());
                if (!activeTimeline.isFake()) {
                    final Timeline.StoragePosition storagePosition =
                            StoragePositionFactory.createStoragePosition(activeTimeline, currentTopicRepo);
                    activeTimeline.setLatestPosition(storagePosition);
                    timelineDbRepository.updateTimelime(activeTimeline);
                }
                timelineDbRepository.updateTimelime(nextTimeline);
                return null;
            } catch (final Exception ex) {
                throw new TimelineException("Failed to switch timeline for event type: " + eventType.getName(), ex);
            } finally {
                try {
                    timelineSync.finishTimelineUpdate(eventType.getName());
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TimelineException("Timeline update was interrupted for %s" + eventType.getName());
                }
            }
        });
    }

}