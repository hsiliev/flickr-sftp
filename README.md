# Flickr SCP Server

A Kotlin-based SSH/SCP server that exposes your Flickr account as a virtual filesystem.

## Features
- **Original Quality**: Photos are served in their original resolution whenever available.
- **Embedded Metadata**: Flickr EXIF tags and descriptions are automatically embedded into the downloaded `.jpg` files.
- **File Timestamps**: Files have accurate creation and modification times based on Flickr's "date taken" and "date posted" metadata.
- **Lazy File Sizes**: Photo sizes are fetched in the background to ensure fast directory listing. Actual sizes will appear in subsequent listings once the background process completes.

**Note:** The filesystem is currently read-only.

## Prerequisites

- Java 17 or higher.
- Flickr API Key and Shared Secret. You can obtain these by [applying for an API key](https://www.flickr.com/services/apps/create/apply) on Flickr.

## Configuration

1. Open `config.properties` in the root directory.
2. Fill in your Flickr API credentials and optional logging configuration:
   ```properties
   flickr.apiKey=YOUR_API_KEY
   flickr.sharedSecret=YOUR_SHARED_SECRET

   # Optional: Configure logging
   org.slf4j.simpleLogger.defaultLogLevel=info
   org.slf4j.simpleLogger.log.com.artware.flickr.sftp=debug
   ```

## Getting Started

### 1. Start the Server
Run the following command in your terminal:
```bash
./gradlew run
```

### 2. Authenticate with Flickr
When the server starts, it will attempt to open your default browser automatically to authorize the application.
1. If the browser opens, simply authorize the application.
2. If the browser does not open, copy the URL printed in the terminal and open it manually.
3. Once you click "OK" or "Authorize" on Flickr, the server will automatically receive the verification and continue. There is no need to copy and paste any codes.

Once authenticated, the server will start listening on port `2222`.

## Connecting to the Server

The server is bound to `localhost` and port `2222`. It supports anonymous access—you can connect with any username and no password.

### Using SFTP
```bash
sftp -P 2222 localhost
```

### Using SCP
```bash
scp -P 2222 localhost:/CollectionName/AlbumName/PhotoName.jpg .
```

## Directory Structure

The Flickr content is exposed using the following structure:
- `/[CollectionName]/` - Your Flickr collections.
- `/[CollectionName]/[AlbumName]/` - Albums (photosets) within that collection.
- `/[CollectionName]/[AlbumName]/[PhotoName].jpg` - The photo itself.
