# TwinPrint PDF Studio

TwinPrint is a versatile Android application designed to simplify PDF management and printing. Whether you need to view documents, merge multiple files, or perform manual duplex printing, TwinPrint provides a clean and intuitive interface to get the job done.

## Features

### 📄 Advanced PDF Viewing
* **Pinch-to-Zoom**: Smoothly zoom in and out (up to 5x) to catch every detail.
* **Smart Panning**: Explore large documents with fluid horizontal and vertical navigation.
* **Page Indicator**: Always know your current position in the document.

### 🖨️ Professional Printing Options
* **Single-Sided Print**: Standard printing for quick tasks.
* **Manual Duplex Printing**: A specialized workflow for printers without automatic double-sided support.
    * **Step 1**: Prints even pages.
    * **Flip Timer**: A guided 10-second countdown to help you flip the stack correctly.
    * **Step 2**: Prints odd pages on the reverse side.

### 🔗 PDF Merging & Management
* **Multi-File Selection**: Pick multiple PDFs from your device.
* **Merge Workflow**: Combine different documents into a single PDF.
* **Page Organizer**: Reorder pages, remove unwanted pages, and preview thumbnails before finalizing your merge.

### 🕒 Recent Documents
* **Quick Access**: Your recently opened files are displayed on the home screen with a clean, card-based UI.
* **Persistence**: Easily jump back into your work without searching through your file system.

## Getting Started

### Prerequisites
* Android device running Android 7.0 (API 24) or higher.
* Storage permissions to read PDF files from your device.

### Installation
1. Clone the repository.
2. Open the project in **Android Studio**.
3. Build and run the app on your device or emulator.

## How to Use Manual Duplex
1. Open a PDF and tap **PRINT**.
2. Select **Two Sided Print**.
3. Tap **STEP 1: Even Pages** and wait for the printer to finish.
4. When the "FLIP THE STACK" timer appears, remove the printed pages, flip them according to your printer's requirements, and put them back in the tray.
5. Once the timer finishes, tap **STEP 2: Odd Pages**.

## Technologies Used
* **Language**: Kotlin
* **UI Framework**: Android XML with Material Components
* **PDF Engine**: Android `PdfRenderer`
* **Printing**: Android `PrintManager` & `PrintDocumentAdapter`
* **Navigation**: Modern `OnBackPressedDispatcher` for intuitive system back button support.

---
Developed as a lightweight, user-centric tool for mobile document management.