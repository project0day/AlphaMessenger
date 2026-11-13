//! Comprehensive error types for database operations.

use thiserror::Error;

/// All errors that can arise from database operations.
#[derive(Error, Debug)]
pub enum DatabaseError {
    /// A rusqlite operation failed.
    #[error("SQLite error: {0}")]
    Sqlite(#[from] rusqlite::Error),

    /// Failed to open the database file.
    #[error("Failed to open database at '{path}': {source}")]
    OpenFailed {
        path: String,
        #[source]
        source: rusqlite::Error,
    },

    /// The encryption passphrase was rejected or decryption failed.
    #[error("Database decryption failed: passphrase may be incorrect")]
    DecryptionFailed,

    /// A migration failed to apply.
    #[error("Migration v{version} failed: {message}")]
    MigrationFailed { version: u64, message: String },

    /// A required record was not found.
    #[error("Record not found: {entity} with id '{id}'")]
    NotFound { entity: String, id: String },

    /// A constraint was violated (e.g., unique, foreign key).
    #[error("Constraint violation: {0}")]
    ConstraintViolation(String),

    /// An invalid enum value was encountered in the database.
    #[error("Invalid enum value '{value}' for type '{type_name}'")]
    InvalidEnumValue { type_name: String, value: String },

    /// Serialization or deserialization failure.
    #[error("Serialization error: {0}")]
    Serialization(#[from] serde_json::Error),

    /// The database pool is closed and cannot be used.
    #[error("Database pool is closed")]
    PoolClosed,

    /// Post-rekey integrity verification failed (C2 Stage 2). The database did
    /// NOT verify cleanly under the new key — the caller must roll back to the
    /// pre-rekey backup.
    #[error("post-rekey integrity check failed: {0}")]
    IntegrityCheckFailed(String),

    /// A UUID parsing or generation error.
    #[error("UUID error: {0}")]
    Uuid(#[from] uuid::Error),

    /// A query operation failed.
    #[error("Query failed: {0}")]
    QueryFailed(String),

    /// A generic / unexpected error.
    #[error("{0}")]
    Other(String),
}

/// Convenience type alias for results in this crate.
pub type Result<T> = std::result::Result<T, DatabaseError>;
