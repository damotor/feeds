# Feeds Android App

This app parses an RSS feeds list and serves a simple HTML page with the latest posts.

Just add your feeds to the `feeds.csv` file inside the `Documents` folder.
Lines beginning with `#` are ignored, allowing you to add comments or temporarily disable feeds.

## CSV Format
`language, title, rss_url`

Example:
`en,Slashdot,https://rss.slashdot.org/Slashdot/slashdotMain`
