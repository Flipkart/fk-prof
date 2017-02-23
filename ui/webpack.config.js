'use strict'

const webpack = require('webpack');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const path = require('path');
const CleanWebpackPlugin = require('clean-webpack-plugin');
const CaseSensitivePathsPlugin = require('case-sensitive-paths-webpack-plugin');
const UnusedFilesWebpackPlugin = require('unused-files-webpack-plugin')['default'];
const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;

const isDevelopment = process.env.NODE_ENV !== 'production';
const buildPath = path.resolve('public');
const appPath = path.resolve('app', 'index.js');

const common = {
  entry: {
    app: [appPath],
    vendor: ['react', 'react-dom', 'react-router', 'redux', 'react-redux',
      'redux-thunk', 'isomorphic-fetch'],
  },
  output: {
    path: buildPath,
    publicPath: '/public/',
    filename: '[name].bundle.js',
  },
  module: {
    loaders: [
      {
        test: /\.jsx?$/,
        exclude: /node_modules/,
        loaders: ['babel'],
      }, {
        test: /\.json?$/,
        loaders: ['json'],
      }, {
        test: /\.woff(2)?(\?v=[0-9]\.[0-9]\.[0-9])?$/,
        loaders: ['url?limit=10000&mimetype=application/font-woff'],
      },
      { test: /\.(ttf|eot|svg)(\?v=[0-9]\.[0-9]\.[0-9])?$/,
        loaders: ['file'],
      },
      {
        test: /\.s?css$/,
        loader: 'style!css?modules&localIdentName=[name]---[local]---[hash:base64:5]!postcss',
        exclude: [
          /node_modules/,
          /assets\/styles/,
        ],
      },
      {
        test: /\.s?css$/,
        loader: 'style!css!postcss',
        include: [/node_modules/, /assets\/styles/],
      },
    ],
  },
  resolve: {
    alias: {
      components: path.resolve('app', 'components'),
      containers: path.resolve('app', 'containers'),
      reducers: path.resolve('app', 'reducers'),
      actions: path.resolve('app', 'actions'),
      serializers: path.resolve('app', 'serializers'),
      utils: path.resolve('app', 'utils'),
      api: path.resolve('app', 'api'),
    },
  },
  plugins: [
    new webpack.optimize.CommonsChunkPlugin({
      names: ['vendor', 'manifest'],
    }),
    new webpack.optimize.OccurrenceOrderPlugin(),
    new webpack.optimize.DedupePlugin(),
    new webpack.NoErrorsPlugin(),
    new CaseSensitivePathsPlugin(),
    new BundleAnalyzerPlugin({
      analyzerMode: 'static',
      reportFilename: 'webpack-bundle-report.html',
      openAnalyzer: false,
      generateStatsFile: false,
      statsOptions: null,
      logLevel: 'info',
    }),
  ],
  postcss: [
    require('precss'),
    require('autoprefixer'),
  ],
};

let finalConf;
if (isDevelopment) {
  finalConf = Object.assign({}, common);
  finalConf.devtool = 'source-map';
  finalConf.entry.app = ['webpack-hot-middleware/client'].concat(finalConf.entry.app);
  finalConf.plugins = finalConf.plugins.concat([
    new HtmlWebpackPlugin({
      template: path.resolve(__dirname, 'index.tpl.html'),
      chunksSortMode: 'dependency',
    }),
    new webpack.HotModuleReplacementPlugin(),
    new UnusedFilesWebpackPlugin(),
    new webpack.DefinePlugin({
      'process.env.NODE_ENV': JSON.stringify('development'),
      __DEV__: JSON.stringify('true'),
    })]);
} else {
  // production config
  finalConf = Object.assign({}, common);
  finalConf.output.filename = '[name].bundle.[chunkhash].js';
  finalConf.output.chunkFilename = '[name].bundle.[chunkhash].js';
  finalConf.output.publicPath = '/consumption/build/';
  finalConf.devtool = false;
  finalConf.plugins = finalConf.plugins.concat([
    new HtmlWebpackPlugin({
      template: path.resolve('app/index.tpl.html'),
      chunksSortMode: 'dependency',
    }),
    new webpack.optimize.UglifyJsPlugin({
      compress: {
        warnings: false,
      },
    }),
    new CleanWebpackPlugin([buildPath]),
    new webpack.DefinePlugin({
      'process.env.NODE_ENV': '"production"',
      __DEV__: '""',
    }),
  ]);
}

module.exports = finalConf;
