const fs = require('node:fs');
const path = require('node:path');
const dir = path.resolve(__dirname, '../client/dist');
let html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const artwork = 'data:image/svg+xml;base64,' + fs.readFileSync(path.join(dir, 'inspiration.svg')).toString('base64');
html = html.replace(/<script[^>]*src="([^"]+)"[^>]*><\/script>/g, (_, url) => {
  const js = fs.readFileSync(path.join(dir, url.replace(/^\//, '')), 'utf8').replaceAll('/inspiration.svg', artwork).replaceAll('</script', '<\\/script');
  return '<script type="module">' + js + '</script>';
});
html = html.replace(/<link[^>]*rel="stylesheet"[^>]*href="([^"]+)"[^>]*>/g, (_, url) => '<style>' + fs.readFileSync(path.join(dir, url.replace(/^\//, '')), 'utf8') + '</style>');
fs.writeFileSync(path.join(dir, 'common-preview.html'), html);
console.log('Created client/dist/common-preview.html');
