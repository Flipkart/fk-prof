import React, { Component } from 'react';
import styles from './StacklineDetailComponent.css';
import polyfill from 'utils/polyfill';

class StacklineDetailComponent extends Component {
  constructor (props) {
    super(props);
    this.state = {
    };
  }
  
  shouldComponentUpdate(nextProps, nextState) {
    //this.props.style can change because it is generated by react-virtualized
    if(
      (this.props.nodestate !== nextProps.nodestate) || 
      (this.props.highlight !== nextProps.highlight) || 
      (this.props.listIdx !== nextProps.listIdx) ||
      (!Object.is(this.props.style, nextProps.style))
      ) {
      return true;
    }
    return false;
  }

  getStyleAndIconForNode() {
    if (this.props.nodestate) {
      return [styles.collapsedIcon, "play_arrow"];
    } else {
      return ["mdl-color-text--accent", "play_arrow"];
    }
  }

  getIconForHighlight() {
    return this.props.highlight ? "highlight" : "lightbulb_outline";
  }

  render () {
    let leftPadding = (this.props.indent || 0) + "px";
    return (
      <div className={`${this.props.highlight ? styles.highlight : this.props.subdued && styles.subdued} ${styles.detailContainer}`} style={this.props.style}>
        <div className={styles.stackline} style={{marginLeft: leftPadding}} title={this.props.nodename}>
          <div className={`material-icons mdl-color-text--primary ${styles.nodeIcon}`} onClick={this.props.onHighlight}>
            {this.getIconForHighlight()}
          </div>
          <div className={styles.stacklineInner} onClick={this.props.onClick}>
            <div className={`material-icons ${this.getStyleAndIconForNode()[0]} ${styles.nodeIcon}`}>
              {this.getStyleAndIconForNode()[1]}
            </div>
            <div className={styles.stacklineText} style={{flex: "1 1 auto", whiteSpace: "nowrap"}}>{this.props.stackline}</div>
          </div>
        </div>
      </div>
    );
  }
}

export default StacklineDetailComponent;