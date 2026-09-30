using System;
using Microsoft.Data.SqlClient;

namespace easyTradeManager
{
    /// <summary>
    /// Helpers for shaping the SQL Server connection string for high-throughput operation.
    /// </summary>
    public static class DatabaseConnection
    {
        /// <summary>Default max pool size when none is configured. Raised from the ADO.NET
        /// default of 100 because manager carries the highest request volume and its three
        /// DbContexts share a single pool keyed on the connection string.</summary>
        public const int DefaultMaxPoolSize = 200;

        /// <summary>
        /// Returns the connection string with an explicit <c>Max Pool Size</c> applied. If the
        /// caller-supplied connection string already sets a pool size, that value is preserved.
        /// Uses <see cref="SqlConnectionStringBuilder"/> so existing keys are never duplicated
        /// or corrupted.
        /// </summary>
        public static string WithPoolSize(string connectionString, string configuredMaxPoolSize)
        {
            if (string.IsNullOrWhiteSpace(connectionString))
            {
                // Nothing to shape; let the downstream provider surface the missing-config error.
                return connectionString ?? string.Empty;
            }

            var builder = new SqlConnectionStringBuilder(connectionString);

            // Respect an explicit pool size already present in the connection string.
            if (!connectionString.Contains("Max Pool Size", StringComparison.OrdinalIgnoreCase))
            {
                builder.MaxPoolSize =
                    int.TryParse(configuredMaxPoolSize, out var configured) && configured > 0
                        ? configured
                        : DefaultMaxPoolSize;
            }

            // Pooling is on by default, but be explicit for clarity at scale.
            builder.Pooling = true;

            return builder.ConnectionString;
        }
    }
}
