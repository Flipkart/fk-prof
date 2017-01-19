'use strict'

const express = require('express');
const compression = require('compression');
const cookieParser = require('cookie-parser');
const path = require('path');

const env = process.env.NODE_ENV;
const isDevelopment = env !== 'production';
const port = isDevelopment ? 3001 : process.env.PORT;
const app = express();
const publicPath = path.resolve(__dirname, 'public');

app.use(cookieParser());

if (isDevelopment) {
  const webpack = require('webpack');
  const webpackConfig = require('./webpack.config.js');
  const webpackMiddleware = require('webpack-dev-middleware');
  const webpackHotMiddleware = require('webpack-hot-middleware');
  const compiler = webpack(webpackConfig);
  app.use(webpackMiddleware(compiler, {
    publicPath: webpackConfig.output.publicPath,
    stats: {
      colors: true,
      hash: false,
      timings: true,
      chunks: false,
      chunkModules: false,
      modules: false,
    },
  }));
  app.use(webpackHotMiddleware(compiler));
}

if (isDevelopment) {
  app.use(express.static(publicPath));
} else {
  app.use(compression());
  app.use(express.static(publicPath, {
    setHeaders: (res) => {
      res.append('Cache-Control', 'public, max-age=31536000');
    },
  }));
}

app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public/index.html'));
});

app.listen(port, () => {
  console.log(`fk-prof-ui is listening at http://localhost:${port}`);
});
