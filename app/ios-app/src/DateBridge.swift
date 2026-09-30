import Foundation

/// Kotlin dates cross into Swift as epoch days (days since 1970-01-01, no time zone).
enum DateBridge {
    private static let utc: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }()

    /// The local-calendar date for an epoch day (for `DatePicker`).
    static func date(fromEpochDay epochDay: Int64) -> Date {
        let parts = utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: TimeInterval(epochDay) * 86_400))
        return Calendar.current.date(from: parts) ?? Date()
    }

    /// The epoch day of a local-calendar date.
    static func epochDay(from date: Date) -> Int64 {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        let utcDate = utc.date(from: parts) ?? date
        return Int64((utcDate.timeIntervalSince1970 / 86_400).rounded(.down))
    }
}
