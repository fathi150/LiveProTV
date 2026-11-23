function doGet(e) {
  var deviceId = (e.parameter.device_id || "").trim().toUpperCase();
  var sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName("Devices");
  var data = sheet.getDataRange().getValues();

  // Columns: A: device_id | B: active (TRUE/FALSE) | C: expiry | D: host | E: username | F: password | G: m3u_url
  for (var i = 1; i < data.length; i++) {
    if (String(data[i][0]).trim().toUpperCase() === deviceId) {
      var res = {
        active: data[i][1] === true || String(data[i][1]).toUpperCase() === "TRUE",
        expiry: String(data[i][2]),
        host: String(data[i][3]),
        username: String(data[i][4]),
        password: String(data[i][5]),
        m3u_url: String(data[i][6]),
        message: "OK"
      };
      return ContentService.createTextOutput(JSON.stringify(res)).setMimeType(ContentService.MimeType.JSON);
    }
  }

  return ContentService.createTextOutput(JSON.stringify({
    active: false,
    message: "الجهاز غير مسجل: " + deviceId
  })).setMimeType(ContentService.MimeType.JSON);
}
