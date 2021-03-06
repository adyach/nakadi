package org.zalando.nakadi.service.converter;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.zalando.nakadi.domain.CursorError;
import org.zalando.nakadi.domain.EventType;
import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.domain.Timeline;
import org.zalando.nakadi.exceptions.InternalNakadiException;
import org.zalando.nakadi.exceptions.InvalidCursorException;
import org.zalando.nakadi.exceptions.NoSuchEventTypeException;
import org.zalando.nakadi.exceptions.ServiceUnavailableException;
import org.zalando.nakadi.repository.db.EventTypeCache;
import org.zalando.nakadi.service.CursorConverter;
import org.zalando.nakadi.service.timeline.TimelineService;
import org.zalando.nakadi.view.Cursor;
import org.zalando.nakadi.view.SubscriptionCursorWithoutToken;

import java.util.ArrayList;
import java.util.List;

import static org.zalando.nakadi.util.CursorConversionUtils.NUMBERS_ONLY_PATTERN;

class VersionOneConverter implements VersionedConverter {
    private static final int TIMELINE_ORDER_LENGTH = 4;
    private static final int TIMELINE_ORDER_BASE = 16;

    private final EventTypeCache eventTypeCache;
    private final TimelineService timelineService;

    VersionOneConverter(final EventTypeCache eventTypeCache, final TimelineService timelineService) {
        this.eventTypeCache = eventTypeCache;
        this.timelineService = timelineService;
    }

    @Override
    public CursorConverter.Version getVersion() {
        return CursorConverter.Version.ONE;
    }

    @Override
    public List<NakadiCursor> convertBatched(final List<SubscriptionCursorWithoutToken> cursors) throws
            InvalidCursorException, InternalNakadiException, NoSuchEventTypeException, ServiceUnavailableException {
        // For version one it doesn't matter - batched or not
        final List<NakadiCursor> result = new ArrayList<>(cursors.size());
        for (final SubscriptionCursorWithoutToken cursor: cursors) {
            result.add(convert(cursor.getEventType(), cursor));
        }
        return result;
    }

    public NakadiCursor convert(final String eventTypeStr, final Cursor cursor)
            throws InternalNakadiException, NoSuchEventTypeException, InvalidCursorException {
        final String[] parts = cursor.getOffset().split("-", 3);
        if (parts.length != 3) {
            throw new InvalidCursorException(CursorError.INVALID_OFFSET);
        }
        final String versionStr = parts[0];
        if (versionStr.length() != CursorConverter.VERSION_LENGTH) {
            throw new InvalidCursorException(CursorError.INVALID_OFFSET);
        }
        final String orderStr = parts[1];
        if (orderStr.length() != TIMELINE_ORDER_LENGTH) {
            throw new InvalidCursorException(CursorError.INVALID_OFFSET);
        }
        final String offsetStr = parts[2];
        if (offsetStr.isEmpty() || !NUMBERS_ONLY_PATTERN.matcher(offsetStr).matches()) {
            throw new InvalidCursorException(CursorError.INVALID_OFFSET);
        }
        final int order;
        try {
            order = Integer.parseInt(orderStr, TIMELINE_ORDER_BASE);
        } catch (final NumberFormatException ex) {
            throw new InvalidCursorException(CursorError.INVALID_OFFSET);
        }
        final List<Timeline> timelines = eventTypeCache.getTimelinesOrdered(eventTypeStr);
        if (timelines.isEmpty()) {
            // Timeline probably was there some time ago, but now it is rolled back.
            // Therefore one should create NakadiCursor with version zero, checking that order is almost default one.
            Preconditions.checkArgument(
                    order == (Timeline.STARTING_ORDER + 1), "Fallback supported only for order next after initial");
            final EventType eventType = eventTypeCache.getEventType(eventTypeStr);
            return new NakadiCursor(
                    timelineService.getFakeTimeline(eventType),
                    cursor.getPartition(),
                    StringUtils.leftPad(offsetStr, VersionZeroConverter.VERSION_ZERO_MIN_OFFSET_LENGTH, '0')
            );
        }
        final Timeline timeline = timelines.stream()
                .filter(t -> t.getOrder() == order)
                .findAny()
                .orElseThrow(() -> new InvalidCursorException(CursorError.UNAVAILABLE));
        return new NakadiCursor(
                timeline,
                cursor.getPartition(),
                offsetStr);
    }

    public String formatOffset(final NakadiCursor nakadiCursor) {
        // Cursor is made of fields.
        // version - 3 symbols
        // order - 4 symbols
        // offset data - everything else
        return String.format(
                "%s-%04x-%s",
                CursorConverter.Version.ONE.code,
                nakadiCursor.getTimeline().getOrder(),
                nakadiCursor.getOffset());
    }
}
